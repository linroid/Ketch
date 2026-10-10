package com.linroid.ketch.server

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.remote.RemoteKetch
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/** A [KetchServer] over a real engine, read by a [RemoteKetch] over a real socket. */
class RemoteConnectionsTest {

  /** Answers a ranged 1 MiB file whose segment requests never finish. */
  private class StalledEngine : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>) = ServerInfo(
      contentLength = 1024 * 1024,
      acceptRanges = true,
      etag = "\"v1\"",
      lastModified = null,
    )

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      onData(ByteArray(1024))
      awaitCancellation()
    }

    override fun close() {}
  }

  @Test
  fun remote_seesTheConnectionsOfAnHttpDownload_andEndsWhenTheServerStops() = runTest {
    withContext(Dispatchers.Default) {
      withTimeout(30_000) {
        val folder = Files.createTempDirectory("ketch-remote-connections").toFile()
        val ketch = Ketch(httpEngine = StalledEngine())
        val server = KetchServer(
          ketch,
          port = 0,
          allowedDirectories = listOf(folder.path),
          mdnsEnabled = false,
        )
        var remote: RemoteKetch? = null
        try {
          ketch.start()
          server.start(wait = false)
          val client = RemoteKetch("127.0.0.1", server.port()).also { remote = it }
          val task = ketch.download(
            DownloadRequest(
              url = "https://files.example:8443/big.bin",
              destination = Destination(folder.path + "/"),
              connections = 3,
            ),
          )

          val open = client.activeConnections(limit = 2).first { it.total == 3 }

          assertEquals(2, open.connections.size)
          for (connection in open.connections) {
            assertEquals(task.taskId, connection.taskId)
            assertEquals("http", connection.source)
            assertEquals("files.example", connection.host)
            assertEquals(8443, connection.port)
          }

          val streaming = CompletableDeferred<Unit>()
          val ended = async {
            runCatching {
              client.activeConnections().onEach { streaming.complete(Unit) }.collect()
            }.exceptionOrNull()
          }
          streaming.await()
          server.stop()
          assertNotNull(ended.await(), "The stream should fail once the server stops")
        } finally {
          remote?.close()
          server.stop()
          ketch.close()
          folder.deleteRecursively()
        }
      }
    }
  }
}
