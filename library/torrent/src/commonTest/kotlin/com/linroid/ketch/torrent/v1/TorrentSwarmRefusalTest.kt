package com.linroid.ketch.torrent.v1

import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.api.TorrentStallReason
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
import okio.Path
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A v1 swarm against a Fast peer (BEP 6) that has every piece but refuses piece 0, rejecting
 * each of its blocks, until it announces the piece again: the swarm leaves the piece to other
 * peers meanwhile, asks the peer again once it announces it or a minute later, and calls a
 * stall for it missing pieces rather than an unknown reason, also while it asks again. Two
 * pieces of four blocks.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentSwarmRefusalTest {
  private val pieceLength = 65_536
  private val payload = ByteArray(2 * pieceLength) { (it * 31 + 7).toByte() }
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "data", "length" to payload.size.toLong(), "piece length" to pieceLength.toLong(),
    "pieces" to sha1Digest(payload.copyOfRange(0, pieceLength)) +
      sha1Digest(payload.copyOfRange(pieceLength, payload.size)),
  ))))

  @Test
  fun refusedPiece_announcedAgain_isAskedForAgain() = refusalTest { rig ->
    rig.refusing.await()
    rig.peerAnnounces.complete(Unit)
    rig.swarm.join()
  }

  @Test
  fun refusedPiece_isAskedForAgainAMinuteLater() = refusalTest { rig ->
    rig.refusing.await()
    rig.servesAll.complete(Unit)
    // Asked for nothing while the refusal stands, then for the piece once a minute passed.
    rig.advanceTo(55_000)
    assertTrue(rig.swarm.isActive)
    rig.advanceTo(65_000)
    rig.swarm.join()
  }

  @Test
  fun refusedPiece_stallsAsMissing() = refusalTest { rig ->
    rig.refusing.await()
    rig.advanceTo(35_000)
    val stall = rig.stall.value
    assertEquals(TorrentStallReason.MISSING_PIECES, stall?.reason, "Stall: $stall")
    assertEquals(1, stall?.missingPieces)
    rig.peerAnnounces.complete(Unit)
    rig.swarm.join()
  }

  @Test
  fun refusedPiece_refusedAgainAMinuteLater_staysMissingThroughout() =
    refusalTest(holdsAskedAgain = true) { rig ->
      rig.refusing.await()
      rig.advanceTo(35_000)
      // Every stall published from now on, which the peer's refusal must not leave.
      val reasons = mutableListOf<TorrentStallReason?>()
      val following = launch { rig.stall.collect { reasons += it?.reason } }
      // Asked again a minute later, the peer takes its time to reject the piece once more.
      rig.advanceTo(65_000)
      rig.askedAgain.await()
      rig.advanceTo(70_000)
      rig.refuseAgain.complete(Unit)
      rig.advanceTo(75_000)
      following.cancelAndJoin()
      assertEquals(listOf(TorrentStallReason.MISSING_PIECES), reasons.distinct(),
        "Stalls: $reasons")
      assertEquals(1, rig.stall.value?.missingPieces)
      rig.peerAnnounces.complete(Unit)
      rig.swarm.join()
    }

  private inner class Rig(
    val clock: AtomicLong,
    val stall: MutableStateFlow<TorrentStall?>,
    /** Completes once the peer rejected piece 0 and the swarm stopped asking for it. */
    val refusing: CompletableDeferred<Unit>,
    /** Has the peer announce piece 0 (HAVE) and serve it from then on. */
    val peerAnnounces: CompletableDeferred<Unit>,
    /** Has the peer serve piece 0 from then on, without saying so. */
    val servesAll: CompletableDeferred<Unit>,
    /** Completes once the swarm asks for piece 0 again after its refusal, held back by the peer. */
    val askedAgain: CompletableDeferred<Unit>,
    /** Has the peer reject the requests it held back, and any more for piece 0. */
    val refuseAgain: CompletableDeferred<Unit>,
    val swarm: Job,
  ) {
    /** Moves the clock on in 5 s steps, each longer than the swarm's tick, up to [until]. */
    suspend fun advanceTo(until: Long) {
      while (clock.load() < until) {
        delay(150)
        clock.addAndFetch(5_000)
      }
      delay(300)
    }
  }

  /**
   * Runs a swarm on a test clock against the refusing peer, which [body] drives, and checks the
   * swarm completes. A peer that [holdsAskedAgain] answers requests for piece 0 that come after
   * its refusal only once [Rig.refuseAgain] completes.
   */
  private fun refusalTest(
    holdsAskedAgain: Boolean = false,
    body: suspend CoroutineScope.(Rig) -> Unit,
  ) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        coroutineScope {
          val root: Path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-refusal-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val store = TorrentPieceStore(metadata, root / "data", emptySet(), "refusal")
          val network = createTorrentNetwork()
          val budget = TorrentBufferBudget(4 * 1024 * 1024)
          val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
          val peers = Channel<PeerEndpoint>(1)
          peers.send(listener.local)
          peers.close()
          val refusing = CompletableDeferred<Unit>()
          val peerAnnounces = CompletableDeferred<Unit>()
          val servesAll = CompletableDeferred<Unit>()
          val askedAgain = CompletableDeferred<Unit>()
          val refuseAgain = CompletableDeferred<Unit>()
          if (!holdsAskedAgain) refuseAgain.complete(Unit)
          val peer = launch {
            val connection = listener.accept()
            try {
              val wire = PeerWire(connection, metadata)
              wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false,
                false, fast = true))
              servePiecesButZero(wire, refusing, peerAnnounces, servesAll, askedAgain,
                refuseAgain)
            } catch (_: IOException) {
              // The swarm closes its peers once it is done.
            } catch (e: CancellationException) {
              throw e
            } finally {
              connection.close()
              listener.close()
            }
          }
          val clock = AtomicLong(0)
          val stall = MutableStateFlow<TorrentStall?>(null)
          val swarm = launch {
            TorrentSwarm(store, network, budget, nowMs = { clock.load() }, stall = stall)
              .run(peers)
          }
          try {
            body(Rig(clock, stall, refusing, peerAnnounces, servesAll, askedAgain, refuseAgain,
              swarm))
            assertTrue(store.completed())
            assertContentEquals(payload, torrentFileSystem.read(root / "data") { readByteArray() })
            assertEquals(0, budget.allocated)
          } finally {
            swarm.cancelAndJoin()
            peer.cancelAndJoin()
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }

  /**
   * Plays a peer with every piece that serves piece 1 and rejects every request for piece 0 until
   * [announces] or [servesAll] completes, then serves it too, having sent a HAVE for it on
   * [announces]. [refusing] completes once the swarm asked for nothing for a while after a
   * rejection. Requests for piece 0 after that wait for [refuseAgain] to be rejected, and
   * complete [askedAgain].
   */
  private suspend fun CoroutineScope.servePiecesButZero(
    wire: PeerWire,
    refusing: CompletableDeferred<Unit>,
    announces: CompletableDeferred<Unit>,
    servesAll: CompletableDeferred<Unit>,
    askedAgain: CompletableDeferred<Unit>,
    refuseAgain: CompletableDeferred<Unit>,
  ) {
    val messages = Channel<PeerMessage>(Channel.UNLIMITED)
    val reader = launch {
      try {
        while (true) messages.send(wire.read())
      } catch (e: IOException) {
        messages.close(e)
      }
    }
    try {
      wire.send(PeerMessage.HaveAll)
      wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
      var rejected = false
      val held = mutableListOf<PeerMessage.Reject>()
      while (true) {
        if (announces.isCompleted && !servesAll.isCompleted) {
          wire.send(PeerMessage.Have(0))
          servesAll.complete(Unit)
        }
        if (refuseAgain.isCompleted && held.isNotEmpty()) {
          wire.sendAll(held)
          held.clear()
        }
        val message = withTimeoutOrNull(300) { messages.receive() }
        if (message == null) {
          if (rejected) refusing.complete(Unit)
          continue
        }
        if (message !is PeerMessage.Request) continue
        if (message.index == 0 && !servesAll.isCompleted) {
          val reject = PeerMessage.Reject(message.index, message.begin, message.length)
          if (refusing.isCompleted && !refuseAgain.isCompleted) {
            held += reject
            askedAgain.complete(Unit)
          } else {
            wire.send(reject)
            rejected = true
          }
        } else {
          val start = message.index * pieceLength + message.begin
          wire.send(PeerMessage.Piece(message.index, message.begin,
            payload.copyOfRange(start, start + message.length)))
        }
      }
    } finally {
      reader.cancel()
    }
  }
}
