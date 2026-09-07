package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.file.FileAccessor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class KotlinTorrentSourceTest {
  private object NoOpFileAccessor : FileAccessor {
    override suspend fun writeAt(offset: Long, data: ByteArray): Unit = error("Source owns I/O")
    override suspend fun flush(): Unit = error("Source owns I/O")
    override fun close() = Unit
    override suspend fun delete(): Unit = error("Source owns I/O")
    override suspend fun size(): Long = error("Source owns I/O")
    override suspend fun preallocate(size: Long): Unit = error("Source owns I/O")
  }

  private class Http(val metainfo: ByteArray, val peer: PeerEndpoint) : HttpEngine {
    var closed = false
    val events = mutableListOf<String>()
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo = error("unused")
    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      if (url.contains(".torrent")) onData(metainfo) else {
        events += url.substringAfter("event=", "").substringBefore('&')
        onData(Bencode.encode(mapOf("interval" to 30L, "peers" to listOf(
          mapOf("ip" to peer.host, "port" to peer.port.toLong())
        ))))
      }
    }
    override fun close() { closed = true }
  }

  @Test
  fun selectedBoundaryFiles_downloadAndRestartThroughPublicSource() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        coroutineScope {
          val network = createTorrentNetwork()
          val listener = network.listen(PeerEndpoint("127.0.0.1", 0))
          val bytes = ByteArray(8) { it.toByte() }
          val metainfo = Bencode.encode(mapOf("announce" to "http://tracker/announce",
            "info" to mapOf("name" to "bundle", "piece length" to 4L,
              "pieces" to sha1Digest(bytes.copyOfRange(0, 4)) +
                sha1Digest(bytes.copyOfRange(4, 8)),
              "files" to listOf(
                mapOf("length" to 3L, "path" to listOf("skip-a")),
                mapOf("length" to 2L, "path" to listOf("selected")),
                mapOf("length" to 3L, "path" to listOf("skip-b"))
              ))))
          val metadata = TorrentMetadata.fromBencode(metainfo)
          val http = Http(metainfo, listener.local)
          val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false), http)
          val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
            "ketch-source-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
          val output = root / "custom-name"
          val server = launch {
            while (isActive) {
              val connection = listener.accept()
              launch {
                try {
                  val wire = PeerWire(connection, metadata)
                  wire.handshake(PeerHandshake(metadata.infoHash, torrentRandomBytes(20), false, false))
                  wire.send(PeerMessage.Bitfield(byteArrayOf(192.toByte())))
                  wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
                  while (true) {
                    val message = wire.read()
                    if (message is PeerMessage.Request) {
                      val begin = message.index * 4 + message.begin
                      wire.send(PeerMessage.Piece(message.index, message.begin,
                        bytes.copyOfRange(begin, begin + message.length)))
                    }
                  }
                } catch (_: IOException) {
                  // The completed downloader closes its peer socket.
                } finally { connection.close() }
              }
            }
          }
          fun context(resolved: ResolvedSource?) = DownloadContext(
            taskId = "selected-task", url = "http://fixture/bundle.torrent",
            request = DownloadRequest(url = "http://fixture/bundle.torrent",
              selectedFileIds = setOf("1")),
            fileAccessor = NoOpFileAccessor, segments = MutableStateFlow(emptyList()),
            onProgress = { downloaded, total ->
              assertEquals(2L, total)
              assertTrue(downloaded in 0..2)
            },
            throttle = {}, headers = emptyMap(), preResolved = resolved,
            outputPath = output.toString(),
          )
          try {
            val resolved = source.resolve("http://fixture/bundle.torrent", emptyMap())
            assertEquals(8L, resolved.totalBytes)
            val first = context(resolved)
            source.download(first)
            assertEquals(listOf(1), first.segments.value.map { it.index })
            assertEquals(2L, first.segments.value.single().downloadedBytes)
            assertContentEquals(byteArrayOf(3, 4),
              FileSystem.SYSTEM.read(output / "selected") { readByteArray() })
            assertFalse(FileSystem.SYSTEM.exists(output / "skip-a"))
            assertFalse(FileSystem.SYSTEM.exists(output / "skip-b"))
            assertFalse("completed" in http.events) // Only a subset of the torrent is present.
            val saved = assertNotNull(source.updateResumeState(first))
            source.close()
            server.cancelAndJoin()
            network.close()
            val restored = TorrentDownloadSource(TorrentConfig(dhtEnabled = false))
            try {
              val second = context(null)
              restored.resume(second, saved)
              assertEquals(2L, second.segments.value.single().downloadedBytes)
              restored.cleanup(second, assertNotNull(restored.updateResumeState(second)))
              assertFalse(FileSystem.SYSTEM.exists(output / "selected"))
            } finally { restored.close() }
            assertFalse(http.closed)
          } finally {
            source.close()
            server.cancelAndJoin()
            network.close()
            FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
          }
        }
      }
    }
  }
}
