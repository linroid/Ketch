package com.linroid.ketch.remote

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.api.SystemInfo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RemoteProxyTest {
  private val proxy = ProxyConfig.manual("socks5://127.0.0.1:1080")

  @Test
  fun proxy_serverWithoutFeature_isRefusedBeforeSending() = runTest {
    val paths = mutableListOf<String>()
    val remote = RemoteKetch("remote.example", 8642, null, false, MockEngine { request ->
      paths += request.url.encodedPath
      respond(
        Json.encodeToString(status(features = emptySet())),
        HttpStatusCode.OK,
        headersOf(HttpHeaders.ContentType, "application/json"),
      )
    })
    try {
      assertFailsWith<UnsupportedOperationException> {
        remote.updateConfig(DownloadConfig(proxy = proxy))
      }
      assertFailsWith<UnsupportedOperationException> {
        remote.download(DownloadRequest("https://example.com/file", proxy = proxy))
      }
      // Only the status was asked for; nothing was changed or added.
      assertEquals(listOf("/api/status", "/api/status"), paths)
    } finally {
      remote.close()
    }
  }

  @Test
  fun systemProxy_isSentWithoutAskingForFeatures() = runTest {
    val paths = mutableListOf<String>()
    val remote = RemoteKetch("remote.example", 8642, null, false, MockEngine { request ->
      paths += request.url.encodedPath
      respond("", HttpStatusCode.NoContent)
    })
    try {
      remote.updateConfig(DownloadConfig())
      assertEquals(listOf("/api/config"), paths)
    } finally {
      remote.close()
    }
  }

  private fun status(features: Set<String>) = KetchStatus(
    name = "NAS",
    version = "1",
    revision = "r",
    uptime = 0,
    config = DownloadConfig(),
    system = SystemInfo(
      os = "Linux",
      arch = "x64",
      separator = "/",
      javaVersion = "21",
      availableProcessors = 4,
      maxMemory = 0,
      totalMemory = 0,
      freeMemory = 0,
      downloadDirectory = "/srv",
      totalSpace = 0,
      freeSpace = 0,
      usableSpace = 0,
    ),
    features = features,
  )
}
