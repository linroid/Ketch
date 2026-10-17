package com.linroid.ketch.torrent.v1

import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentListener
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.peer.pieceBitfield
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import okio.IOException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A v1 swarm dials a peer it downloaded from again after the peer drops, for about half an hour
 * while it downloads, so a peer that comes back is reached again: one we dialed, one that dialed
 * us, or one whose connection to us replaced ours. The swarm runs on a test clock, moved on in
 * steps longer than its loop's tick, so minutes of backoff pass in seconds.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentSwarmRedialTest {
  private val pieces = listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8))
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "data", "length" to 8L, "piece length" to 4L,
    "pieces" to sha1Digest(pieces[0]) + sha1Digest(pieces[1])))))

  @Test
  fun droppedPeer_isDialedAgainUntilItIsBack() = swarmTest { rig ->
    rig.dialedPeerSendsAPieceAndDrops()
    // Dialed again 5, 20 and 50 s after it dropped, all refused; then 110 s after.
    val reached = rig.reachAgain(upAt = 70_000)
    assertTrue(reached - 70_000 <= 60_000, "Reached ${reached - 70_000} ms after it came back")
  }

  @Test
  fun droppedPeer_foundAgain_isDialedThirtySecondsAfterItsLastDial() = swarmTest { rig ->
    rig.dialedPeerSendsAPieceAndDrops()
    // Its last refused dial, 110 s after it dropped, set the next two minutes later. Found again
    // as it comes back, it is dialed 30 s after that dial instead.
    val reached = rig.reachAgain(upAt = 120_000) { rig.peers.send(rig.listener.local) }
    assertTrue(reached - 120_000 <= 30_000, "Reached ${reached - 120_000} ms after it came back")
  }

  @Test
  fun droppedPeer_foundAgainEveryMinute_isDialedSoonerOnlyEveryFiveMinutes() = swarmTest { rig ->
    rig.dialedPeerSendsAPieceAndDrops()
    val dropped = rig.dials.load()
    // The DHT names it every minute. On 20 s steps it is dialed at 20, 40 and 80 s, at 120 s
    // (found then, 30 s after its last dial) and at 240 s; found at 420 s, five minutes after it
    // was brought forward, it is dialed then, and not again until 720 s.
    rig.advanceTo(600_000, step = 20_000) { time ->
      if (time % 60_000 == 0L) rig.peers.send(rig.listener.local)
    }
    val dials = rig.dials.load() - dropped
    assertTrue(dials <= 7, "Dialed $dials times in ten minutes")
    rig.reachAgain(upAt = 600_000) { rig.peers.send(rig.listener.local) }
  }

  @Test
  fun droppedPeer_isGivenUpAfterTenDials() = swarmTest { rig ->
    rig.dialedPeerSendsAPieceAndDrops()
    val dropped = rig.dials.load()
    // Dialed again after 5, 15, 30, 60 and 120 s, then every five minutes: the tenth time 29
    // minutes after it dropped. Then it is given up, and forgotten five minutes later.
    rig.advanceTo(2_400_000, step = 60_000)
    assertEquals(10, rig.dials.load() - dropped)
    // A source naming it again brings it back as any new peer.
    rig.reachAgain(upAt = 2_400_000) { rig.peers.send(rig.listener.local) }
  }

  @Test
  fun peerUpFiveMinutes_isDialedAgainAsAtFirst() = swarmTest { rig ->
    rig.down.store(true)
    rig.peers.send(rig.listener.local)
    // Refused three times, it answers its fourth dial with nothing we want.
    while (rig.dials.load() < 3) rig.advanceTo(rig.clock.load() + 5_000)
    rig.down.store(false)
    val accepted = async { rig.listener.accept() }
    while (!accepted.isCompleted) rig.advanceTo(rig.clock.load() + 5_000)
    val connection = accepted.await()
    val wire = PeerWire(connection, metadata)
    wire.handshake(rig.peerHandshake())
    wire.send(PeerMessage.Bitfield(pieceBitfield(BooleanArray(2))))
    // It drops five minutes later. Its fourth dial would have spent its retries, but a
    // connection that lasted counts as its first dial: it is dialed again 5 s later.
    rig.advanceTo(rig.clock.load() + 300_000, step = 300_000)
    rig.down.store(true)
    connection.close()
    delay(300)
    val before = rig.dials.load()
    rig.advanceTo(rig.clock.load() + 5_000)
    delay(300)
    assertEquals(before + 1, rig.dials.load())
    rig.reachAgain(upAt = rig.clock.load(), served = intArrayOf(0, 1))
  }

  @Test
  fun lonelyPeer_isDialedEveryMinuteOnceTheDownloadStalls() = swarmTest(stall = true) { rig ->
    rig.dialedPeerSendsAPieceAndDrops()
    // Its own dials come 5, 20, 50 and 110 s after it dropped, then two and five minutes apart.
    // Without a peer, the stalled swarm also dials it every minute from 60 s on.
    val reached = rig.reachAgain(upAt = 250_000, step = 10_000)
    assertTrue(reached - 250_000 <= 60_000, "Reached ${reached - 250_000} ms after it came back")
  }

  @Test
  fun lonelyStall_keepsDialingAPeerNamedOnlyOnce() = swarmTest(stall = true) { rig ->
    // The only source named it once, and it is gone for longer than its own dials last.
    rig.dialedPeerSendsAPieceAndDrops()
    // Given up on, it is still known: the stalled swarm dials it every minute until it is back.
    val reached = rig.reachAgain(upAt = 2_700_000, step = 30_000)
    val late = reached - 2_700_000
    assertTrue(late <= 90_000, "Reached $late ms after it came back")
  }

  @Test
  fun lonelyStall_stillForgetsAPeerItGaveUpOn() = swarmTest(stall = true) { rig ->
    // It refuses from the start: dialed at 0, 10, 30 and 60 s on 10 s steps, then given up.
    rig.down.store(true)
    rig.peers.send(rig.listener.local)
    // Without a peer, the stalled swarm dials it again every minute from 60 s on. Those dials
    // leave when it was given up, so found again five minutes after that it starts over.
    rig.advanceTo(400_000, step = 10_000)
    val before = rig.dials.load()
    // Found again, it is dialed as a new endpoint is: at once, then 5, 15 and 30 s later.
    rig.peers.send(rig.listener.local)
    rig.advanceTo(460_000, step = 10_000)
    val dials = rig.dials.load() - before
    assertTrue(dials >= 4, "Dialed $dials times in the minute after it was found again")
    rig.reachAgain(upAt = 460_000, step = 10_000, served = intArrayOf(0, 1))
  }

  @Test
  fun peerThatDialedUs_isDialedWhereItListensUntilItIsBack() = swarmTest { rig ->
    // It dials us, says where it listens, sends piece 0 and drops.
    val (connection, wire) = rig.dialSwarm(listenPort = rig.listener.local.port)
    serve(wire, 0)
    rig.down.store(true)
    connection.close()
    // Dialed where it listens 5, 20 and 50 s after it dropped, then 110 s after.
    val reached = rig.reachAgain(upAt = 70_000)
    assertTrue(reached - 70_000 <= 60_000, "Reached ${reached - 70_000} ms after it came back")
  }

  @Test
  fun peerKeptOverOurDial_isDialedThereAgainUntilItIsBack() = swarmTest(
    // We listen on a port above the peer's, so both keep the connection the peer dialed.
    listenPortFor = { it.port + 1 },
  ) { rig ->
    rig.peers.send(rig.listener.local)
    val dialed = rig.listener.accept()
    val dialedWire = PeerWire(dialed, metadata)
    dialedWire.handshake(rig.peerHandshake())
    serve(dialedWire, 0)
    // It dials us too, without saying where it listens; that connection replaces ours.
    val (connection, wire) = rig.dialSwarm()
    assertTrue(wire.read() is PeerMessage.Bitfield)
    assertNotNull(withTimeoutOrNull(5_000) {
      try {
        while (true) dialedWire.read()
      } catch (_: IOException) {
      }
    }, "The connection we dialed was kept too")
    dialed.close()
    rig.down.store(true)
    connection.close()
    // Where we dialed it is dialed again as it would have been had that connection dropped.
    val reached = rig.reachAgain(upAt = 70_000)
    assertTrue(reached - 70_000 <= 60_000, "Reached ${reached - 70_000} ms after it came back")
  }

  @Test
  fun waitingEndpoints_leaveRoomForNewOnes() = swarmTest { rig ->
    // Over half the book refuses, and waits to be dialed again.
    val refusing = 2_100
    repeat(refusing) { rig.peers.send(PeerEndpoint("10.${it / 250}.${it % 250}.1", 6881)) }
    while (rig.dials.load() < refusing) delay(10)
    // Every result handled.
    delay(500)
    rig.peers.send(rig.listener.local)
    val connection = assertNotNull(withTimeoutOrNull(5_000) { rig.listener.accept() },
      "A new peer found no room among endpoints that wait")
    try {
      val wire = PeerWire(connection, metadata)
      wire.handshake(rig.peerHandshake())
      wire.send(PeerMessage.Bitfield(pieceBitfield(booleanArrayOf(true, true))))
      wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
      try {
        while (true) {
          val message = wire.read()
          if (message is PeerMessage.Request) {
            wire.send(PeerMessage.Piece(message.index, 0, pieces[message.index]))
          }
        }
      } catch (_: IOException) {
        // The swarm completed.
      }
    } finally {
      connection.close()
    }
  }

  private inner class Rig(
    val clock: AtomicLong,
    val down: AtomicBoolean,
    val dials: AtomicInt,
    val transport: TorrentNetwork,
    /** Where the peer listens; dials of other endpoints are refused. */
    val listener: TorrentListener,
    val peers: Channel<PeerEndpoint>,
    val incoming: Channel<TorrentConnection>,
    private val scope: CoroutineScope,
  ) {
    val peerId: ByteArray = torrentRandomBytes(20)
    private val jobs = mutableListOf<Job>()

    fun peerHandshake(extensions: Boolean = false) =
      PeerHandshake(metadata.infoHash, peerId, extensions, false)

    /** The swarm dials the peer, which sends piece 0, then drops and refuses dials. */
    suspend fun dialedPeerSendsAPieceAndDrops() {
      peers.send(listener.local)
      val connection = listener.accept()
      try {
        serve(PeerWire(connection, metadata).also { it.handshake(peerHandshake()) }, 0)
        down.store(true)
      } finally {
        connection.close()
      }
    }

    /** The peer dials the swarm; with [listenPort], it says it listens there (BEP 10 `p`). */
    suspend fun dialSwarm(listenPort: Int? = null): Pair<TorrentConnection, PeerWire> {
      val ours = transport.listen(PeerEndpoint("127.0.0.1", 0))
      val theirs = try {
        val client = transport.connect(ours.local)
        incoming.send(ours.accept())
        client
      } finally {
        ours.close()
      }
      val wire = PeerWire(theirs, metadata)
      wire.handshake(peerHandshake(extensions = listenPort != null))
      if (listenPort != null) {
        wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
          "m" to emptyMap<String, Long>(), "p" to listenPort.toLong()))))
      }
      return theirs to wire
    }

    /** Moves the clock on in [step]s, each longer than the swarm's tick, up to [until]. */
    suspend fun advanceTo(
      until: Long,
      step: Long = 5_000,
      onStep: suspend (Long) -> Unit = {},
    ) {
      while (clock.load() < until) {
        delay(110)
        onStep(clock.addAndFetch(step))
      }
    }

    /**
     * Lets dials through once the clock reaches [upAt], when [onUp] runs, and moves the clock
     * on in [step]s until the swarm dials the peer, which sends the [served] pieces. Returns
     * when it was reached.
     */
    suspend fun reachAgain(
      upAt: Long,
      step: Long = 5_000,
      served: IntArray = intArrayOf(1),
      onUp: suspend () -> Unit = {},
    ): Long {
      val reached = CompletableDeferred<Long>()
      jobs += scope.launch {
        val connection = listener.accept()
        try {
          reached.complete(clock.load())
          val wire = PeerWire(connection, metadata)
          wire.handshake(peerHandshake())
          serve(wire, *served)
          // Until the swarm, complete, closes the connection.
          try {
            while (true) wire.read()
          } catch (_: IOException) {
          }
        } finally {
          connection.close()
        }
      }
      if (clock.load() >= upAt) {
        down.store(false)
        onUp()
      }
      while (!reached.isCompleted && clock.load() < upAt + 600_000) {
        delay(110)
        if (clock.addAndFetch(step) >= upAt && down.load()) {
          down.store(false)
          onUp()
        }
      }
      return assertNotNull(reached.takeIf { it.isCompleted }?.await(),
        "The swarm never dialed the peer again")
    }

    suspend fun close() {
      jobs.forEach { it.cancelAndJoin() }
    }
  }

  /** Answers as a peer that has only the [served] pieces, and sends each once asked. */
  private suspend fun serve(wire: PeerWire, vararg served: Int) {
    wire.send(PeerMessage.Bitfield(pieceBitfield(BooleanArray(2) { it in served })))
    wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    var left = served.size
    while (left > 0) {
      val message = wire.read()
      if (message is PeerMessage.Request) {
        wire.send(PeerMessage.Piece(message.index, 0, pieces[message.index]))
        left--
      }
    }
  }

  /**
   * Runs a swarm with no peer but the one [body] plays, on a test clock, and checks it completes.
   * With [stall], the swarm looks for stalls and recovers from them.
   */
  private fun swarmTest(
    stall: Boolean = false,
    listenPortFor: (PeerEndpoint) -> Int = { 0 },
    body: suspend CoroutineScope.(Rig) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        coroutineScope {
          val clock = AtomicLong(0)
          val down = AtomicBoolean(false)
          val dials = AtomicInt(0)
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-redial-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val store = TorrentPieceStore(metadata, root / "data", emptySet(), "redial")
          val transport = createTorrentNetwork()
          val listener = transport.listen(PeerEndpoint("127.0.0.1", 0))
          val network = object : TorrentNetwork by transport {
            override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
              dials.addAndFetch(1)
              if (down.load() || remote.port != listener.local.port) {
                throw IOException("Connection refused")
              }
              return transport.connect(remote)
            }
          }
          val peers = Channel<PeerEndpoint>(Channel.UNLIMITED)
          val incoming = Channel<TorrentConnection>(4)
          val rig = Rig(clock, down, dials, transport, listener, peers, incoming, this)
          val swarm = launch {
            TorrentSwarm(store, network, TorrentBufferBudget(1024 * 1024),
              listenPortFor = listenPortFor, nowMs = { clock.load() },
              stall = if (stall) MutableStateFlow<TorrentStall?>(null) else null)
              .run(peers, incoming)
          }
          try {
            body(rig)
            swarm.join()
            assertContentEquals(pieces[0] + pieces[1],
              torrentFileSystem.read(root / "data") { readByteArray() })
          } finally {
            swarm.cancelAndJoin()
            rig.close()
            listener.close()
            transport.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }
}
