package com.linroid.ketch.torrent.v1

import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentConnection
import com.linroid.ketch.torrent.net.TorrentNetwork
import com.linroid.ketch.torrent.net.createTorrentNetwork
import com.linroid.ketch.torrent.peer.PeerHandshake
import com.linroid.ketch.torrent.peer.PeerMessage
import com.linroid.ketch.torrent.peer.PeerWire
import com.linroid.ketch.torrent.peer.pieceBitfield
import com.linroid.ketch.torrent.storage.TorrentPieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.IOException
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A v1 swarm dials a peer it downloaded from again after the peer drops, as long as it downloads,
 * so a peer that comes back is reached again. The swarm runs on a test clock, moved on in steps
 * longer than its loop's tick, so minutes of backoff pass in seconds.
 */
@OptIn(ExperimentalAtomicApi::class)
class TorrentSwarmRedialTest {
  private val pieces = listOf(byteArrayOf(1, 2, 3, 4), byteArrayOf(5, 6, 7, 8))
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "data", "length" to 8L, "piece length" to 4L,
    "pieces" to sha1Digest(pieces[0]) + sha1Digest(pieces[1])))))

  @Test
  fun droppedPeer_isDialedAgainUntilItIsBack() {
    // Dialed again 5, 20 and 50 s after it dropped, all refused; then 110 s after.
    val reached = reachAgain(upAt = 70_000)
    assertTrue(reached - 70_000 <= 60_000, "Reached ${reached - 70_000} ms after it came back")
  }

  @Test
  fun droppedPeer_foundAgain_isDialedThirtySecondsAfterItsLastDial() {
    // Its last refused dial, 110 s after it dropped, set the next two minutes later. Found again
    // as it comes back, it is dialed 30 s after that dial instead.
    val reached = reachAgain(upAt = 120_000) { peers, endpoint -> peers.send(endpoint) }
    assertTrue(reached - 120_000 <= 30_000, "Reached ${reached - 120_000} ms after it came back")
  }

  /**
   * Runs a swarm whose only peer sends piece 0 and drops, then refuses every dial until the
   * swarm's clock reaches [upAt], when [onUp] runs, and sends piece 1 once reached again. Returns
   * when, on the swarm's clock, it was reached.
   */
  private fun reachAgain(
    upAt: Long,
    onUp: suspend (SendChannel<PeerEndpoint>, PeerEndpoint) -> Unit = { _, _ -> },
  ): Long {
    var reachedAt = 0L
    runTest {
      withContext(Dispatchers.Default) {
        withTimeout(30_000) {
          coroutineScope {
            val clock = AtomicLong(0)
            val down = AtomicBoolean(false)
            val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
              "ketch-redial-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
            val store = TorrentPieceStore(metadata, root / "data", emptySet(), "redial")
            val transport = createTorrentNetwork()
            val network = object : TorrentNetwork by transport {
              override suspend fun connect(remote: PeerEndpoint): TorrentConnection {
                if (down.load()) throw IOException("Connection refused")
                return transport.connect(remote)
              }
            }
            val listener = transport.listen(PeerEndpoint("127.0.0.1", 0))
            val peerId = torrentRandomBytes(20)
            val dropped = CompletableDeferred<Unit>()
            val reached = CompletableDeferred<Long>()
            val peer = launch {
              val first = listener.accept()
              try {
                serve(first, peerId, piece = 0)
                down.store(true)
              } finally {
                first.close()
              }
              dropped.complete(Unit)
              val second = listener.accept()
              try {
                reached.complete(clock.load())
                val wire = serve(second, peerId, piece = 1)
                // Until the swarm, complete, closes the connection.
                try {
                  while (true) wire.read()
                } catch (_: IOException) {
                }
              } finally {
                second.close()
              }
            }
            val peers = Channel<PeerEndpoint>(4)
            peers.send(listener.local)
            val swarm = launch {
              TorrentSwarm(store, network, TorrentBufferBudget(1024 * 1024),
                nowMs = { clock.load() }).run(peers)
            }
            try {
              dropped.await()
              while (!reached.isCompleted && clock.load() < 600_000) {
                // Longer than the swarm's tick, so it looks at every step.
                delay(110)
                if (clock.addAndFetch(5_000) >= upAt && down.load()) {
                  down.store(false)
                  onUp(peers, listener.local)
                }
              }
              reachedAt = assertNotNull(reached.takeIf { it.isCompleted }?.await(),
                "The swarm never dialed the peer again")
              swarm.join()
              assertContentEquals(pieces[0] + pieces[1],
                torrentFileSystem.read(root / "data") { readByteArray() })
            } finally {
              swarm.cancelAndJoin()
              peer.cancelAndJoin()
              listener.close()
              transport.close()
              torrentFileSystem.deleteRecursively(root, mustExist = false)
            }
          }
        }
      }
    }
    return reachedAt
  }

  /** Answers the handshake as a peer that has only [piece], and sends it once asked. */
  private suspend fun serve(
    connection: TorrentConnection,
    peerId: ByteArray,
    piece: Int,
  ): PeerWire {
    val wire = PeerWire(connection, metadata)
    wire.handshake(PeerHandshake(metadata.infoHash, peerId, false, false))
    wire.send(PeerMessage.Bitfield(pieceBitfield(BooleanArray(2) { it == piece })))
    wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
    while (true) {
      val message = wire.read()
      if (message is PeerMessage.Request) {
        wire.send(PeerMessage.Piece(piece, 0, pieces[piece]))
        return wire
      }
    }
  }
}
