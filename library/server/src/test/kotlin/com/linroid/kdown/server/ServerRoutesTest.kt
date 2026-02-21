package com.linroid.kdown.server

import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.api.KDownStatus
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.api.config.DownloadConfig
import com.linroid.kdown.api.config.QueueConfig
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
    val status = json.decodeFromString<KDownStatus>(
      response.bodyAsText()
    )
    assertEquals(KDownApi.VERSION, status.version)
    assertNotNull(status.revision)
  }

  @Test
  fun `status includes uptime`() = testApplication {
    application {
      val server = createTestServer()
      with(server) { configureServer() }
    }
    val response = client.get("/api/status")
    val status = json.decodeFromString<KDownStatus>(
      response.bodyAsText()
    )
    assertTrue(status.uptime >= 0)
  }

  @Test
  fun `status includes download config`() = testApplication {
    val downloadConfig = DownloadConfig(
      defaultDirectory = "/tmp/test-downloads",
      maxConnections = 8,
      retryCount = 5,
      queueConfig = QueueConfig(
        maxConcurrentDownloads = 6,
        maxConnectionsPerHost = 2,
      ),
    )
    val kdown = createTestKDown(config = downloadConfig)
    application {
      val server = createTestServer(kdown = kdown)
      with(server) { configureServer() }
    }
    val response = client.get("/api/status")
    val status = json.decodeFromString<KDownStatus>(
      response.bodyAsText()
    )
    assertEquals(
      "/tmp/test-downloads",
      status.config.defaultDirectory,
    )
    assertEquals(8, status.config.maxConnections)
    assertEquals(5, status.config.retryCount)
    assertEquals(
      6,
      status.config.queueConfig.maxConcurrentDownloads,
    )
    assertEquals(
      2,
      status.config.queueConfig.maxConnectionsPerHost,
    )
  }

  @Test
  fun `status includes system info`() = testApplication {
    application {
      val server = createTestServer()
      with(server) { configureServer() }
    }
    val response = client.get("/api/status")
    val status = json.decodeFromString<KDownStatus>(
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
    val status = json.decodeFromString<KDownStatus>(
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
