package com.linroid.ketch.remote

import com.linroid.ketch.api.Checksum
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.SystemInfo
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class RemoteChecksumTest {
  private val checksum = Checksum.parse("sha256:" + "ab".repeat(32))

  @Test
  fun checksum_serverWithoutFeature_isRefusedBeforeSending() = runTest {
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
        remote.download(DownloadRequest("https://example.com/file", checksum = checksum))
      }
      assertEquals(listOf("/api/status"), paths)
    } finally {
      remote.close()
    }
  }

  @Test
  fun checksum_serverWithFeature_isSentAsText() = runTest {
    val bodies = mutableListOf<String>()
    val remote = RemoteKetch("remote.example", 8642, null, false, MockEngine { request ->
      if (request.url.encodedPath == "/api/status") {
        respond(
          Json.encodeToString(status(features = setOf(KetchFeatures.CHECKSUM))),
          HttpStatusCode.OK,
          headersOf(HttpHeaders.ContentType, "application/json"),
        )
      } else {
        bodies += request.body.toByteArray().decodeToString()
        // Refused, so no task view is needed: only the body sent matters here.
        respond("", HttpStatusCode.InternalServerError)
      }
    })
    try {
      runCatching {
        remote.download(DownloadRequest("https://example.com/file", checksum = checksum))
      }
      assertTrue(bodies.single().contains("\"checksum\":\"$checksum\""), bodies.single())
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
