package com.linroid.ketch.torrent

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import okio.Buffer
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentPeerDownloaderTest {
  @Test
  fun keepaliveChatterCannotExtendPayloadDeadline() = runTest {
    val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
      "name" to "file", "piece length" to 1L, "length" to 1L,
      "pieces" to sha1Digest(byteArrayOf(1))
    ))))
    val input = Buffer()
      .write(PeerWire.encodeHandshake(PeerHandshake(metadata.infoHash, ByteArray(20), false, false)))
      .write(PeerWire.encode(PeerMessage.Have(0)))
      .write(PeerWire.encode(PeerMessage.Control(PeerMessage.Signal.UNCHOKE)))
    var closed = false
    val connection = object : TorrentConnection {
      override val remote = PeerEndpoint("127.0.0.1", 1)
      override suspend fun readExactly(size: Int): ByteArray {
        if (input.size > 0) return input.readByteArray(size.toLong())
        if (size > 0) delay(1_000)
        return ByteArray(size)
      }
      override suspend fun write(bytes: ByteArray) = Unit
      override fun close() { closed = true }
    }
    val delegate = createTorrentNetwork()
    val network = object : TorrentNetwork by delegate {
      override suspend fun connect(remote: PeerEndpoint) = connection
    }
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-chatter-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    try {
      val store = TorrentPieceStore(metadata, root / "file", emptySet(), "test")
      val downloader = TorrentPeerDownloader(store, network, timeSource = testScheduler.timeSource)
      assertFailsWith<TimeoutCancellationException> { downloader.download(connection.remote) }
      assertEquals(30_000L, testScheduler.currentTime)
      assertTrue(closed)
      assertFalse(store.completed())
    } finally {
      delegate.close()
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun download_selectedFilesAcrossPieceBoundaries_areVerified() = runTest {
    transfer(corrupt = false)
  }

  @Test
  fun download_corruptPeer_doesNotReportOrPersistProgress() = runTest {
    transfer(corrupt = true)
  }

  private suspend fun transfer(corrupt: Boolean) = withContext(Dispatchers.Default) {
    withTimeout(15_000) {
      coroutineScope {
        val bytes = ByteArray(40_001) { (it * 13).toByte() }
        val hashes = bytes.asList().chunked(20_000)
          .fold(ByteArray(0)) { result, chunk -> result + sha1Digest(chunk.toByteArray()) }
        val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
          "name" to "pack", "piece length" to 20_000L, "pieces" to hashes,
          "files" to listOf(
            mapOf("length" to 17_000L, "path" to listOf("first")),
            mapOf("length" to 1_000L, "path" to listOf("skip")),
            mapOf("length" to 22_001L, "path" to listOf("last"))
          )
        ))))
        val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
          "ketch-transfer-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
        val network = createTorrentNetwork()
        val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
        val store = TorrentPieceStore(metadata, root / "pack", setOf(0, 2), "test")
        val server = async {
          val connection = listener.accept()
          try {
            val wire = PeerWire(connection, metadata)
            wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
            wire.send(PeerMessage.Bitfield(pieceBitfield(BooleanArray(3) { true })))
            wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
            var sent = 0
            while (sent < bytes.size) {
              val request = wire.read()
              if (request is PeerMessage.Request) {
                val start = request.index * 20_000 + request.begin
                val block = bytes.copyOfRange(start, start + request.length)
                if (corrupt) block[0] = (block[0].toInt() xor 1).toByte()
                wire.send(PeerMessage.Piece(request.index, request.begin, block))
                sent += block.size
                if (corrupt && sent == 20_000) break
              }
            }
          } finally {
            connection.close()
          }
        }
        var progress = 0L
        val downloader = TorrentPeerDownloader(store, network, onProgress = { progress = it })
        try {
          if (corrupt) {
            assertFailsWith<IllegalArgumentException> { downloader.download(listener.local) }
            assertEquals(0L, progress)
            assertFalse(store.completed())
            assertContentEquals(booleanArrayOf(false, false, false), store.verifiedPieces())
          } else {
            downloader.download(listener.local)
            assertEquals(39_001L, progress)
            assertTrue(store.completed())
            assertContentEquals(bytes.copyOfRange(0, 17_000),
              torrentFileSystem.read(root / "pack/first") { readByteArray() })
            assertContentEquals(bytes.copyOfRange(18_000, bytes.size),
              torrentFileSystem.read(root / "pack/last") { readByteArray() })
            assertFalse(torrentFileSystem.exists(root / "pack/skip"))
          }
          server.await()
        } finally {
          network.close()
          server.cancelAndJoin()
          torrentFileSystem.deleteRecursively(root, mustExist = false)
        }
      }
    }
  }
}
