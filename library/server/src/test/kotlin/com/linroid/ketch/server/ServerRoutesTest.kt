package com.linroid.ketch.server

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SpeedLimitMode
import com.linroid.ketch.api.SpeedSettings
import com.linroid.ketch.api.DownloadCategory
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.DownloadSource
import com.linroid.ketch.core.speed.SpeedModeOptions
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.ResolveUrlRequest
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.testing.testApplication
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.IOException
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
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
  fun resolve_logsTheRedactedUrl() = testApplication {
    val ketch = createTestKetch()
    val lines = java.util.Collections.synchronizedList(mutableListOf<String>())
    val capture = object : Logger {
      override fun v(message: String) { lines += message }
      override fun d(message: String) { lines += message }
      override fun i(message: String) { lines += message }
      override fun w(message: String, throwable: Throwable?) { lines += message }
      override fun e(message: String, throwable: Throwable?) { lines += message }
    }
    application {
      val server = createTestServer(ketch)
      with(server) { configureServer() }
    }
    // Ketch installs its own logger when it is created.
    KetchLogger.setLogger(capture)
    try {
      client.post("/api/resolve") {
        contentType(ContentType.Application.Json)
        setBody(
          json.encodeToString(
            ResolveUrlRequest("https://user:hunter2@example.com/file.zip?token=s3cret")
          )
        )
      }
    } finally {
      KetchLogger.setLogger(Logger.None)
    }
    val line = lines.first { "POST /api/resolve" in it }
    assertFalse("hunter2" in line, line)
    assertFalse("s3cret" in line, line)
    assertTrue("example.com/file.zip" in line, line)
  }

  /** Refuses every `refuse:` URL with a reason, as the torrent source refuses large pieces. */
  private class RefusingSource : DownloadSource<Unit> {
    override val type = "refusing"
    override val stateSerializer = Unit.serializer()
    override fun canHandle(input: ResolveInput): Boolean =
      input is ResolveInput.Url && input.url.startsWith("refuse:")
    override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource =
      throw KetchError.SourceError(type, detail = "The pieces are too large")
    override suspend fun download(context: DownloadContext, state: Unit?) = Unit
  }

  @Test
  fun resolve_sourceRefusesTheUrl_returnsTheTypedErrorWithItsReason() = testApplication {
    val ketch = Ketch(NoOpHttpEngine(), additionalSources = listOf(RefusingSource()))
    application {
      with(createTestServer(ketch)) { configureServer() }
    }
    val client = createClient { install(ContentNegotiation) { json(json) } }
    try {
      val response = client.post("/api/resolve") {
        contentType(ContentType.Application.Json)
        setBody(ResolveUrlRequest("refuse:large"))
      }
      assertEquals(HttpStatusCode.UnprocessableEntity, response.status)
      val body = response.bodyAsText()
      val error = json.decodeFromString(KetchError.tolerantSerializer(), body)
      assertIs<KetchError.SourceError>(error)
      assertEquals("refusing", error.sourceType)
      assertEquals("The pieces are too large", error.detail)
      // Released clients read every refusal as an ErrorResponse, and keep its reason so.
      val refusal = json.decodeFromString<ErrorResponse>(body)
      assertEquals("source_error", refusal.error)
      assertEquals("The pieces are too large", refusal.message)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun resolve_urlNoSourceHandles_returnsUnsupported() = testApplication {
    application {
      with(createTestServer()) { configureServer() }
    }
    val response = client.post("/api/resolve") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(ResolveUrlRequest("gopher://example.com/file")))
    }
    assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
    val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
    assertEquals("unsupported", error.error)
    assertEquals("No download source handles this URL", error.message)
  }

  /**
   * Takes every `lack:` URL but lacks something for it, as the HTTP source does for a server
   * that answers the wrong range or a proxy variable it cannot use; the rest of the URL is why.
   */
  private class LackingSource : DownloadSource<Unit> {
    override val type = "lacking"
    override val stateSerializer = Unit.serializer()
    override fun canHandle(input: ResolveInput): Boolean =
      input is ResolveInput.Url && input.url.startsWith("lack:")
    override suspend fun resolve(input: ResolveInput, config: DownloadConfig): ResolvedSource =
      throw KetchError.Unsupported(IllegalStateException((input as ResolveInput.Url).url.drop(5)))
    override suspend fun download(context: DownloadContext, state: Unit?) = Unit
  }

  @Test
  fun resolve_sourceLacksSomethingForTheUrl_saysWhyNotThatNoSourceHandlesIt() = testApplication {
    val ketch = Ketch(NoOpHttpEngine(), additionalSources = listOf(LackingSource()))
    application {
      with(createTestServer(ketch)) { configureServer() }
    }
    suspend fun refusal(url: String): ErrorResponse {
      val response = client.post("/api/resolve") {
        contentType(ContentType.Application.Json)
        setBody(json.encodeToString(ResolveUrlRequest(url)))
      }
      assertEquals(HttpStatusCode.UnsupportedMediaType, response.status)
      return json.decodeFromString<ErrorResponse>(response.bodyAsText())
    }
    try {
      val range = refusal("lack:Server returned a different range")
      assertEquals("unsupported", range.error)
      assertEquals("Not supported: Server returned a different range", range.message)
      // A reason that quotes a URL is left out.
      val redirect = refusal("lack:Redirected to https://cdn.example/a?sig=1")
      assertEquals("Not supported", redirect.message)
    } finally {
      ketch.close()
    }
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
  fun `PUT config without timeout and retry fields keeps them`() = testApplication {
    val set = DownloadConfig(
      unlimitedRetries = true,
      maxRetryDelayMs = 5_000,
      idleTimeoutMs = 20_000,
      minSpeed = 1_024,
    )
    val ketch = createTestKetch(config = set)
    application {
      val server = createTestServer(ketch = ketch)
      with(server) { configureServer() }
    }
    // As a client older than idle timeouts sends it.
    val response = client.put("/api/config") {
      contentType(ContentType.Application.Json)
      setBody("""{"retryCount": 5}""")
    }

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(set.copy(retryCount = 5), ketch.status().config)
  }

  @Test
  fun `PUT config cannot set hooks`() = testApplication {
    val ketch = createTestKetch()
    application {
      val server = createTestServer(ketch = ketch)
      with(server) { configureServer() }
    }
    // Hooks run programs on the server's machine: only its own config.toml names them.
    val response = client.put("/api/config") {
      contentType(ContentType.Application.Json)
      setBody(
        """{"maxConcurrentDownloads": 2,
          |"hooks": [{"events": ["completed"], "command": ["sh", "-c", "id"]}]}""".trimMargin()
      )
    }

    assertTrue(response.status == HttpStatusCode.OK || response.status == HttpStatusCode.BadRequest)
    assertFalse("hooks" in response.bodyAsText())
    assertFalse("hooks" in json.encodeToString(DownloadConfig.serializer(), ketch.status().config))
    assertFalse("hooks" in client.get("/api/status").bodyAsText())
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

  @Test
  fun `status lists config persisted only with a save hook`() {
    assertFalse(KetchFeatures.CONFIG_PERSISTED in statusFeatures(createTestServer()))
    val saving = createTestServer(onConfigChanged = {})
    assertTrue(KetchFeatures.CONFIG_PERSISTED in statusFeatures(saving))
  }

  private fun statusFeatures(server: KetchServer): Set<String> {
    var features = emptySet<String>()
    testApplication {
      application { with(server) { configureServer() } }
      val response = client.get("/api/status")
      features = json.decodeFromString<KetchStatus>(response.bodyAsText()).features
    }
    return features
  }

  @Test
  fun `PUT config hands what the instance applied to the save hook`() = testApplication {
    val ketch = createTestKetch()
    val saved = mutableListOf<DownloadConfig>()
    application {
      with(createTestServer(ketch, onConfigChanged = { saved += it })) { configureServer() }
    }
    val response = client.put("/api/config") {
      contentType(ContentType.Application.Json)
      setBody("""{"maxConcurrentDownloads": 2}""")
    }

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(listOf(ketch.status().config), saved)
    assertEquals(2, saved.single().maxConcurrentDownloads)
  }

  @Test
  fun `PUT config in the slow lane saves the standing cap`() = testApplication {
    val ketch = Ketch(
      httpEngine = NoOpHttpEngine(),
      config = DownloadConfig(speedLimit = SpeedLimit.mbps(5)),
      speedModes = SpeedModeOptions(SpeedSettings(mode = SpeedLimitMode.SlowLane)),
    )
    val saved = mutableListOf<DownloadConfig>()
    application {
      with(createTestServer(ketch, onConfigChanged = { saved += it })) { configureServer() }
    }
    val response = client.put("/api/config") {
      contentType(ContentType.Application.Json)
      setBody("""{"maxConcurrentDownloads": 2}""")
    }

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(SpeedLimit.mbps(1), ketch.status().config.speedLimit)
    assertEquals(SpeedLimit.mbps(5), saved.single().speedLimit)
    assertEquals(2, saved.single().maxConcurrentDownloads)
  }

  @Test
  fun `PUT config the instance rejects is not saved`() = testApplication {
    val ketch = createTestKetch()
    val parent = Files.createTempDirectory("ketch-routes")
    val saved = mutableListOf<DownloadConfig>()
    application {
      val server = createTestServer(
        ketch,
        allowedDirectories = listOf(parent.toString()),
        onConfigChanged = { saved += it },
      )
      with(server) { configureServer() }
    }
    val missing = parent.resolve("missing").toString()
    val response = client.put("/api/config") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(DownloadConfig(defaultDirectory = missing)))
    }

    assertEquals(HttpStatusCode.BadRequest, response.status)
    assertEquals(emptyList(), saved)
  }

  @Test
  fun `PUT config with a folder the server refuses is not saved`() = testApplication {
    val ketch = createTestKetch()
    val saved = mutableListOf<DownloadConfig>()
    // Only its own folder: temporary folders are in /tmp on Linux, which createTestServer allows.
    val allowed = Files.createTempDirectory("ketch-allowed").toString()
    application {
      val server = createTestServer(
        ketch,
        allowedDirectories = listOf(allowed),
        onConfigChanged = { saved += it },
      )
      with(server) { configureServer() }
    }
    val outside = Files.createTempDirectory("ketch-outside").toString()
    val response = client.put("/api/config") {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(DownloadConfig(defaultDirectory = outside)))
    }

    assertEquals(HttpStatusCode.Forbidden, response.status)
    assertEquals(emptyList(), saved)
  }

  @Test
  fun `PUT config whose save fails answers config_not_saved and keeps it applied`() =
    testApplication {
      val ketch = createTestKetch()
      application {
        val server = createTestServer(ketch, onConfigChanged = { throw IOException("disk full") })
        with(server) { configureServer() }
      }
      val response = client.put("/api/config") {
        contentType(ContentType.Application.Json)
        setBody("""{"maxConcurrentDownloads": 2}""")
      }

      assertEquals(HttpStatusCode.InternalServerError, response.status)
      val error = json.decodeFromString<ErrorResponse>(response.bodyAsText())
      assertEquals("config_not_saved", error.error)
      assertTrue("disk full" in error.message, error.message)
      assertEquals(2, ketch.status().config.maxConcurrentDownloads)
    }
}
