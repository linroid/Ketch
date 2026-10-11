package com.linroid.ketch.torrent.v1

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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** BEP 5 `PORT`: a v1 swarm tells DHT-capable peers our DHT port and hears theirs. */
class TorrentSwarmDhtPortTest {
  private val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "file", "length" to 4L, "piece length" to 4L,
    "pieces" to sha1Digest(byteArrayOf(1, 2, 3, 4))))))

  /** What a peer saw before our interest, and the DHT nodes the swarm heard of. */
  private class Exchange(val dht: Boolean, val ports: List<Int>, val introduced: List<PeerEndpoint>)

  private fun exchange(trackerOnly: Boolean, check: (Exchange) -> Unit) = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        coroutineScope {
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-dht-port-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val store = TorrentPieceStore(metadata, root / "file", emptySet(), "test")
          store.initialize()
          val network = createTorrentNetwork()
          val nodes = Channel<PeerEndpoint>(8)
          val peers = Channel<PeerEndpoint>(1)
          val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
          val swarm = launch {
            TorrentSwarm(store, network, TorrentBufferBudget(2 * 1024 * 1024),
              allowLocalPeers = true, trackerOnly = trackerOnly, dhtPortFor = { 4321 },
              onDhtNode = { nodes.trySend(it) },
            ).run(peers)
          }
          try {
            peers.send(listener.local)
            val connection = listener.accept()
            try {
              val wire = PeerWire(connection, metadata)
              val theirs = wire.handshake(
                PeerHandshake(metadata.infoHash, torrentRandomBytes(20), true, dht = true))
              val ports = mutableListOf<Int>()
              while (true) {
                val message = wire.read()
                if (message == PeerMessage.Control(PeerMessage.Signal.INTERESTED)) break
                if (message is PeerMessage.Port) ports += message.port
              }
              wire.send(PeerMessage.Port(5555))
              val introduced = listOfNotNull(withTimeoutOrNull(2_000) { nodes.receive() })
              check(Exchange(theirs.dht, ports, introduced))
            } finally {
              connection.close()
            }
          } finally {
            swarm.cancelAndJoin()
            listener.close()
            network.close()
            torrentFileSystem.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }

  @Test
  fun publicSwarm_exchangesDhtPorts() = exchange(trackerOnly = false) {
    assertTrue(it.dht)
    assertEquals(listOf(4321), it.ports)
    assertEquals(listOf(PeerEndpoint("127.0.0.1", 5555)), it.introduced)
  }

  @Test
  fun trackerOnlySwarm_neitherAdvertisesNorHearsDht() = exchange(trackerOnly = true) {
    assertFalse(it.dht)
    assertEquals(emptyList(), it.ports)
    assertNull(it.introduced.firstOrNull())
  }
}
