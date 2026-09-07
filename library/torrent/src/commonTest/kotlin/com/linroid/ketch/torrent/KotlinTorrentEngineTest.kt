package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

class KotlinTorrentEngineTest {
  @Test
  fun completedTorrent_acceptsNewIncomingPeerAndSeedsVerifiedBytes() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(15_000) {
        val bytes = byteArrayOf(8, 9, 10, 11)
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
          "name" to "seed", "length" to 4L, "piece length" to 4L, "pieces" to sha1Digest(bytes)
        ))))
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-incoming-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        FileSystem.SYSTEM.createDirectories(root)
        FileSystem.SYSTEM.write(root / "seed") { write(bytes) }
        val engine = KotlinTorrentEngine(TorrentConfig(dhtEnabled = false,
          uploadPolicy = TorrentUploadPolicy.SEED_AFTER_COMPLETION))
        val remote = createTorrentNetwork()
        try {
          engine.start()
          val session = engine.addTask(TorrentTaskSpec("seed-task", metadata,
            (root / "seed").toString(), emptySet()))
          session.resume()
          val state = session.state.first {
            it == TorrentSessionState.SEEDING || it == TorrentSessionState.STOPPED
          }
          assertEquals(TorrentSessionState.SEEDING, state, session.failure.value?.stackTraceToString())
          val connection = remote.connect(PeerEndpoint("127.0.0.1", engine.listenPort))
          try {
            val wire = PeerWire(connection, metadata)
            wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
            wire.send(PeerMessage.Control(PeerMessage.Signal.INTERESTED))
            while (wire.read() != PeerMessage.Control(PeerMessage.Signal.UNCHOKE)) { }
            wire.send(PeerMessage.Request(0, 0, 4))
            var message = wire.read()
            while (message !is PeerMessage.Piece) message = wire.read()
            assertContentEquals(bytes, message.bytes)
          } finally { connection.close() }
        } finally {
          engine.stop()
          remote.close()
          FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }
}
