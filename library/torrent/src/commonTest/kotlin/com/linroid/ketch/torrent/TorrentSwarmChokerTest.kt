package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.IOException
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A v1 seed shares its four upload slots fairly: a waiting peer gets one in its turn. */
@OptIn(ExperimentalAtomicApi::class)
class TorrentSwarmChokerTest {
  @Test
  fun optimisticUnchokeRotatesToWaitingPeer() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        coroutineScope {
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-choker-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
            "name" to "file", "length" to 1L, "piece length" to 1L,
            "pieces" to sha1Digest(byteArrayOf(1))))))
          val store = TorrentPieceStore(metadata, root / "file", emptySet(), "test")
          store.initialize()
          store.commit(0, byteArrayOf(1))
          val network = createTorrentNetwork()
          val listeners = List(5) { network.listen(PeerEndpoint("127.0.0.1", 0)) }
          // Each peer reports every slot it gets (true) or loses (false).
          val events = Channel<Pair<Int, Boolean>>(64)
          val servers = listeners.mapIndexed { index, listener ->
            launch {
              val connection = listener.accept()
              try {
                val wire = PeerWire(connection, metadata)
                wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false,
                  false))
                wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
                while (true) {
                  when (wire.read()) {
                    PeerMessage.Control(PeerMessage.Signal.UNCHOKE) -> events.send(index to true)
                    PeerMessage.Control(PeerMessage.Signal.CHOKE) -> events.send(index to false)
                    else -> Unit
                  }
                }
              } catch (_: IOException) {
                // The swarm closes its peers when the test ends.
              } finally {
                connection.close()
              }
            }
          }
          val peers = Channel<PeerEndpoint>(5)
          listeners.forEach { peers.trySend(it.local) }
          peers.close()
          val clock = AtomicLong(0)
          val budget = TorrentBufferBudget(2 * 1024 * 1024)
          val swarm = async {
            TorrentSwarm(store, network, budget,
              uploadPolicy = { TorrentUploadPolicy.SEED_AFTER_COMPLETION },
              nowMs = { clock.load() }).run(peers)
          }
          try {
            val unchoked = List(4) { events.receive() }.map { (index, slot) ->
              assertTrue(slot)
              index
            }.toSet()
            delay(300)
            // Every slot is taken until a rotation is due.
            assertTrue(events.tryReceive().isFailure)
            val waiting = (0 until 5).single { it !in unchoked }
            clock.store(30_000)
            // The waiting peer gets a slot, and one of the four hands its slot on.
            val changes = List(2) { events.receive() }
            assertTrue((waiting to true) in changes, "$changes")
            assertEquals(1, changes.count { (index, slot) -> !slot && index in unchoked })
            delay(300)
            assertTrue(events.tryReceive().isFailure)
          } finally {
            swarm.cancelAndJoin()
            servers.forEach { it.cancelAndJoin() }
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
          assertEquals(0, budget.allocated)
        }
      }
    }
  }
}
