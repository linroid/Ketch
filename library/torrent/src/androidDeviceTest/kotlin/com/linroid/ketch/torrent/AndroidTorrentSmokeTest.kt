package com.linroid.ketch.torrent

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.file.FileAccessor
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.Path.Companion.toPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidTorrentSmokeTest {
  @Test
  fun foregroundTransferUsesAndroidSocketsAndSafeFilesystem() = runBlocking {
    withTimeout(20_000) {
      val bytes = byteArrayOf(11, 12, 13, 14)
      val data = Bencode.encode(mapOf("info" to mapOf("name" to "fixture", "length" to 4L,
        "piece length" to 4L, "pieces" to sha1Digest(bytes))))
      val metadata = TorrentMetadata.fromBencode(data)
      val root = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        .resolve("torrent-smoke-${System.nanoTime()}").canonicalFile
      val network = createTorrentNetwork()
      val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
      val server = launch {
        val connection = listener.accept()
        try {
          val wire = PeerWire(connection, metadata)
          wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
          wire.send(PeerMessage.Bitfield(byteArrayOf(128.toByte())))
          wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
          while (true) {
            if (wire.read() is PeerMessage.Request) wire.send(PeerMessage.Piece(0, 0, bytes))
          }
        } catch (_: java.io.IOException) {
          // Completion closes the peer connection.
        } finally { connection.close() }
      }
      val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false))
      val output = root.resolve("nested/output")
      val magnet = MagnetUri(metadata.infoHash,
        explicitPeers = listOf("127.0.0.1:${listener.local.port}")).toUri()
      val context = DownloadContext(taskId = "android-smoke", url = magnet,
        request = DownloadRequest(magnet), fileAccessor = object : FileAccessor {
          override suspend fun writeAt(offset: Long, data: ByteArray) = error("Unexpected core I/O")
          override suspend fun flush() = Unit
          override fun close() = Unit
          override suspend fun delete() = Unit
          override suspend fun size(): Long = 0
          override suspend fun preallocate(size: Long) = Unit
        },
        segments = MutableStateFlow(emptyList()), onProgress = { _, _ -> }, throttle = {},
        headers = emptyMap(), preResolved = source.resolveMetainfo(data),
        outputPath = output.absolutePath,
      )
      try {
        source.download(context)
        assertArrayEquals(bytes, output.readBytes())
        assertEquals(4L, context.segments.value.single().downloadedBytes)
        source.cleanup(context, source.updateResumeState(context))
        assertEquals(false, output.exists())
      } finally {
        source.close()
        server.cancelAndJoin()
        network.close()
        torrentFileSystem.deleteRecursively(root.absolutePath.toPath(), mustExist = false)
      }
    }
  }
}
