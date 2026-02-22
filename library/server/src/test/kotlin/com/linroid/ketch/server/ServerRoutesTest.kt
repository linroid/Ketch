package com.linroid.ketch.server

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.DownloadConfig
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ServerRoutesTest {

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  @Test
  fun `status includes version and revision`() = testApplication {
    application {
      val server = createTestServer()
      with(server) { configureServer() }
    }
    val response = client.get("/api/status")
    assertEquals(HttpStatusCode.OK, response.status)
    val status = json.decodeFromString<KetchStatus>(
      response.bodyAsText()
    )
    assertEquals(KetchApi.VERSION, status.version)
    assertNotNull(status.revision)
  }

  @Test
  fun `status includes uptime`() = testApplication {
    application {
      val server = createTestServer()
      with(server) { configureServer() }
    }
    val response = client.get("/api/status")
    val status = json.decodeFromString<KetchStatus>(
      response.bodyAsText()
    )
    assertTrue(status.uptime >= 0)
  }

  @Test
  fun `status includes download config`() = testApplication {
    val downloadConfig = DownloadConfig(
      defaultDirectory = "/tmp/test-downloads",
      maxConnectionsPerDownload = 8,
      retryCount = 5,
      maxConcurrentDownloads = 6,
      maxConnectionsPerHost = 2,
    )
    val ketch = createTestKetch(config = downloadConfig)
    application {
      val server = createTestServer(ketch = ketch)
      with(server) { configureServer() }
    }
    val response = client.get("/api/status")
    val status = json.decodeFromString<KetchStatus>(
      response.bodyAsText()
    )
    assertEquals(
      "/tmp/test-downloads",
      status.config.defaultDirectory,
    )
    assertEquals(8, status.config.maxConnectionsPerDownload)
    assertEquals(5, status.config.retryCount)
    assertEquals(6, status.config.maxConcurrentDownloads)
    assertEquals(2, status.config.maxConnectionsPerHost)
  }

  @Test
  fun `status includes system info`() = testApplication {
    application {
      val server = createTestServer()
      with(server) { configureServer() }
    }
    val response = client.get("/api/status")
    val status = json.decodeFromString<KetchStatus>(
      response.bodyAsText()
    )
    assertTrue(status.system.os.isNotBlank())
    assertTrue(status.system.arch.isNotBlank())
    assertTrue(status.system.javaVersion.isNotBlank())
    assertTrue(status.system.availableProcessors > 0)
    assertTrue(status.system.maxMemory > 0)
  }

  @Test
  fun `status includes storage info`() = testApplication {
    application {
      val server = createTestServer()
      with(server) { configureServer() }
    }
    val response = client.get("/api/status")
    val status = json.decodeFromString<KetchStatus>(
      response.bodyAsText()
    )
    assertTrue(status.system.downloadDirectory.isNotBlank())
  }

  @Test
  fun `PUT config updates speed limit`() = testApplication {
    application {
      val server = createTestServer()
      with(server) { configureServer() }
    }
    val client = createClient {
      install(ContentNegotiation) { json(json) }
    }
    val newConfig = DownloadConfig(
      speedLimit = SpeedLimit.of(512000),
    )
    val response = client.put("/api/config") {
      contentType(ContentType.Application.Json)
      setBody(newConfig)
    }
    assertEquals(HttpStatusCode.OK, response.status)
    val body = json.decodeFromString<DownloadConfig>(
      response.bodyAsText()
    )
    assertEquals(512000L, body.speedLimit.bytesPerSecond)
  }

  @Test
  fun `PUT config with unlimited speed limit`() =
    testApplication {
      application {
        val server = createTestServer()
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      val newConfig = DownloadConfig(
        speedLimit = SpeedLimit.Unlimited,
      )
      val response = client.put("/api/config") {
        contentType(ContentType.Application.Json)
        setBody(newConfig)
      }
      assertEquals(HttpStatusCode.OK, response.status)
    }
}
