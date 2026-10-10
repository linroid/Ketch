package com.linroid.ketch.server

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.DownloadCategory
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.endpoints.model.ErrorResponse
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
import java.nio.file.Files
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
  fun status_listsFeatures() = testApplication {
    application {
      val server = createTestServer()
      with(server) { configureServer() }
    }
    val response = client.get("/api/status")
    val status = json.decodeFromString<KetchStatus>(response.bodyAsText())
    assertTrue(KetchFeatures.AUTO_CONNECTIONS in status.features)
    assertTrue(KetchFeatures.QUEUE_POSITION in status.features)
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

  @Test
  fun `PUT config with missing folder is rejected and not applied`() =
    testApplication {
      val ketch = createTestKetch()
      val parent = Files.createTempDirectory("ketch-routes")
      application {
        val server = createTestServer(ketch = ketch, allowedDirectories = listOf(parent.toString()))
        with(server) { configureServer() }
      }
      val client = createClient {
        install(ContentNegotiation) { json(json) }
      }
      val missing = parent.resolve("missing").toString()
      val response = client.put("/api/config") {
        contentType(ContentType.Application.Json)
        setBody(DownloadConfig(defaultDirectory = missing))
      }
      assertEquals(HttpStatusCode.BadRequest, response.status)
      val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
      assertTrue(missing in error.message)
      assertEquals(DownloadConfig.Default, ketch.status().config)
    }

  @Test
  fun `PUT config without categories keeps the categories set`() = testApplication {
    val video = DownloadCategory(folder = "Video", extensions = listOf("mp4"))
    val ketch = createTestKetch(config = DownloadConfig(categories = listOf(video)))
    application {
      val server = createTestServer(ketch = ketch)
      with(server) { configureServer() }
    }
    // As a client older than category folders sends it.
    val response = client.put("/api/config") {
      contentType(ContentType.Application.Json)
      setBody("""{"maxConcurrentDownloads": 2}""")
    }

    assertEquals(HttpStatusCode.OK, response.status)
    val config = ketch.status().config
    assertEquals(2, config.maxConcurrentDownloads)
    assertEquals(listOf(video), config.categories)
  }

  @Test
  fun `PUT config with categories replaces them`() = testApplication {
    val video = DownloadCategory(folder = "Video", extensions = listOf("mp4"))
    val ketch = createTestKetch(config = DownloadConfig(categories = listOf(video)))
    application {
      val server = createTestServer(ketch = ketch)
      with(server) { configureServer() }
    }
    val client = createClient {
      install(ContentNegotiation) { json(json) }
    }
    val response = client.put("/api/config") {
      contentType(ContentType.Application.Json)
      setBody(DownloadConfig(categories = emptyList()))
    }

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(emptyList(), ketch.status().config.categories)
  }
}
