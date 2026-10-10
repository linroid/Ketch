package com.linroid.ketch.engine

import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.ConnectionRoute
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.KetchDispatchers
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.file.platformFileSystem
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KetchConnectionsTest {
  @Test
  fun download_fourSegments_showFourConnectionsUntilComplete() = runTest {
    val gate = CompletableDeferred<Unit>()
    val fake = FakeHttpEngine()
    val engine = object : HttpEngine by fake {
      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) {
        gate.await()
        fake.download(url, range, headers, onData)
      }
    }
    withKetch(engine) { ketch, folder ->
      val task = ketch.download(request(folder, connections = 4))

      val open = ketch.activeConnections().first { it.total == 4 }

      assertEquals(4, open.connections.map { it.id }.toSet().size)
      for (connection in open.connections) {
        assertEquals(task.taskId, connection.taskId)
        assertEquals("http", connection.source)
        assertEquals("cdn.example.com", connection.host)
        assertEquals(8443, connection.port)
        assertEquals(true, connection.secure)
        assertEquals(ConnectionRoute.UNKNOWN, connection.route)
      }
      gate.complete(Unit)
      assertIs<DownloadState.Completed>(task.state.first { it.isTerminal })
      assertEquals(0, ketch.activeConnections().first().total)
    }
  }

  @Test
  fun pauseAndCancel_closeEveryConnection() = runTest {
    withKetch(FakeHttpEngine(stallAfterBytes = 50)) { ketch, folder ->
      val task = ketch.download(request(folder, connections = 4))
      val open = ketch.activeConnections().first { it.total == 4 }
      assertTrue(open.connections.all { it.downloadedBytes == 100L })

      task.pause()
      assertEquals(0, ketch.activeConnections().first().total)

      task.resume()
      ketch.activeConnections().first { it.total == 4 }
      task.cancel()
      assertEquals(0, ketch.activeConnections().first { it.total == 0 }.total)
    }
  }

  @Test
  fun setConnections_resegment_replacesConnections() = runTest {
    withKetch(FakeHttpEngine(stallAfterBytes = 50)) { ketch, folder ->
      val task = ketch.download(request(folder, connections = 4))
      val before = ketch.activeConnections().first { it.total == 4 }.connections.map { it.id }

      task.setConnections(2)

      // The stopped batch's connections close and the next batch's open.
      val after = ketch.activeConnections()
        .first { snapshot -> snapshot.total > 0 && snapshot.connections.none { it.id in before } }
      assertTrue(after.connections.all { it.downloadedBytes == 100L })
    }
  }

  @Test
  fun activeConnections_limitOutOfRange_throws() = runTest {
    withKetch(FakeHttpEngine()) { ketch, _ ->
      assertFailsWith<IllegalArgumentException> { ketch.activeConnections(0) }
      assertFailsWith<IllegalArgumentException> {
        ketch.activeConnections(ActiveConnections.MAX_LIMIT + 1)
      }
      assertTrue(KetchFeatures.ACTIVE_CONNECTIONS in ketch.status().features)
    }
  }

  private suspend fun TestScope.withKetch(
    engine: HttpEngine,
    block: suspend (KetchApi, String) -> Unit,
  ) {
    val folder = FileSystem.SYSTEM_TEMPORARY_DIRECTORY / "ketch-connections-test"
    platformFileSystem.createDirectories(folder)
    val dispatcher = StandardTestDispatcher(testScheduler)
    val ketch = Ketch(
      httpEngine = engine,
      config = DownloadConfig(defaultDirectory = folder.toString(), retryCount = 0),
      dispatchers = KetchDispatchers(dispatcher, dispatcher, dispatcher),
    )
    try {
      block(ketch, folder.toString())
    } finally {
      ketch.close()
      platformFileSystem.deleteRecursively(folder)
    }
  }

  private fun request(folder: String, connections: Int) = DownloadRequest(
    url = "https://cdn.example.com:8443/file.bin",
    destination = Destination("$folder/file.bin"),
    connections = connections,
  )
}
