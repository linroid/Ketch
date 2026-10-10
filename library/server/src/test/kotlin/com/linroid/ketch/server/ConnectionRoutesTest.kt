package com.linroid.ketch.server

import com.linroid.ketch.api.ActiveConnection
import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.server.api.ConnectionStreams
import com.linroid.ketch.server.api.ServerJson
import com.linroid.ketch.server.api.connectionRoutes
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.install
import io.ktor.server.cio.CIO as ServerCIO
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.resources.Resources
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import io.ktor.server.testing.testApplication
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

/** The `/api/connections` routes over a scripted [KetchApi.activeConnections]. */
class ConnectionRoutesTest {

  private fun snapshot(total: Int) = ActiveConnections(
    sampledAt = Instant.fromEpochMilliseconds(1_000L + total),
    connections = List(total) { index ->
      ActiveConnection(
        id = index.toLong(),
        taskId = "t1",
        source = "http",
        protocol = "HTTP/1.1",
        host = "files.example",
        port = 443,
        downloadBps = 1_000,
        openedAt = Instant.fromEpochMilliseconds(500),
      )
    },
    total = total,
    downloadBps = 1_000L * total,
  )

  /** Answers [KetchApi.activeConnections] with [connections], recording each limit asked. */
  private class ScriptedKetch(
    delegate: KetchApi,
    private val connections: () -> Flow<ActiveConnections>,
  ) : KetchApi by delegate {
    val limits = mutableListOf<Int>()

    override fun activeConnections(limit: Int): Flow<ActiveConnections> {
      limits += limit
      return connections()
    }
  }

  /** A [KetchApi] whose engine reports no connections, as the interface's default does. */
  private class Unsupported(delegate: KetchApi) : KetchApi by delegate {
    override fun activeConnections(limit: Int): Flow<ActiveConnections> =
      flow { throw UnsupportedOperationException("Active connections are not supported") }
  }

  private fun scripted(connections: () -> Flow<ActiveConnections>) =
    ScriptedKetch(createTestKetch(), connections)

  /**
   * Serves only the connection routes, with [streams] instead of the server's own, on a real
   * socket: the test engine holds a streamed response back until it ends.
   */
  private suspend fun withLiveServer(
    ketch: KetchApi,
    streams: ConnectionStreams,
    block: suspend (HttpClient) -> Unit,
  ) = withContext(Dispatchers.Default) {
    withTimeout(20_000) {
      val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
        install(SSE)
        install(Resources)
        install(ContentNegotiation) { json(ServerJson) }
        routing { connectionRoutes(ketch, streams) }
      }
      server.start(wait = false)
      val port = server.engine.resolvedConnectors().first().port
      val client = HttpClient(ClientCIO) {
        defaultRequest { url("http://127.0.0.1:$port") }
      }
      try {
        block(client)
      } finally {
        client.close()
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
      }
    }
  }

  private suspend fun HttpResponse.error(): ErrorResponse =
    ServerJson.decodeFromString(bodyAsText())

  private fun eventNames(body: String): List<String> =
    Regex("event: ?(\\w+)").findAll(body).map { it.groupValues[1] }.toList()

  /** Reads the next event of an SSE [channel] as its name and data. */
  private suspend fun ByteReadChannel.nextEvent(): Pair<String, String> {
    var name = ""
    val data = StringBuilder()
    while (true) {
      val line = readUTF8Line() ?: error("The stream ended")
      if (line.isEmpty()) {
        if (name.isNotEmpty()) return name to data.toString()
        continue
      }
      when (line.substringBefore(':')) {
        "event" -> name = line.substringAfter(':').trim()
        "data" -> data.append(line.substringAfter(':').removePrefix(" "))
      }
    }
  }

  @Test
  fun get_answersTheFirstSnapshot() = testApplication {
    val ketch = scripted { flow { emit(snapshot(2)); emit(snapshot(3)) } }
    application { with(createTestServer(ketch)) { configureServer() } }

    val response = client.get("/api/connections?limit=5")

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(snapshot(2), ServerJson.decodeFromString(response.bodyAsText()))
    assertEquals(listOf(5), ketch.limits)
  }

  @Test
  fun get_withoutLimit_usesTheDefault() = testApplication {
    val ketch = scripted { flow { emit(snapshot(0)) } }
    application { with(createTestServer(ketch)) { configureServer() } }

    assertEquals(HttpStatusCode.OK, client.get("/api/connections").status)
    assertEquals(listOf(ActiveConnections.DEFAULT_LIMIT), ketch.limits)
  }

  @Test
  fun limitOutOfRange_answers400() = testApplication {
    val ketch = scripted { flow { emit(snapshot(0)) } }
    application { with(createTestServer(ketch)) { configureServer() } }

    for (path in listOf("/api/connections", "/api/connections/events")) {
      for (limit in listOf(0, -1, ActiveConnections.MAX_LIMIT + 1)) {
        val response = client.get("$path?limit=$limit")
        assertEquals(HttpStatusCode.BadRequest, response.status, "$path $limit")
        assertEquals("invalid_limit", response.error().error)
      }
      assertEquals(HttpStatusCode.BadRequest, client.get("$path?limit=many").status, path)
    }
    assertEquals(emptyList(), ketch.limits)
  }

  @Test
  fun get_unsupported_answers501() = testApplication {
    application { with(createTestServer(Unsupported(createTestKetch()))) { configureServer() } }

    val response = client.get("/api/connections")

    assertEquals(HttpStatusCode.NotImplemented, response.status)
    assertEquals("unsupported", response.error().error)
  }

  @Test
  fun get_realEngine_answersAnEmptySnapshot() = testApplication {
    application { with(createTestServer()) { configureServer() } }

    val response = client.get("/api/connections")

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(0, ServerJson.decodeFromString<ActiveConnections>(response.bodyAsText()).total)
  }

  @Test
  fun withToken_refusesRequestsWithoutIt() = testApplication {
    application {
      with(KetchServer(createTestKetch(), apiToken = "secret-token")) { configureServer() }
    }

    assertEquals(HttpStatusCode.Unauthorized, client.get("/api/connections").status)
    assertEquals(HttpStatusCode.Unauthorized, client.get("/api/connections/events").status)
    val allowed = client.get("/api/connections") {
      header(HttpHeaders.Authorization, "Bearer secret-token")
    }
    assertEquals(HttpStatusCode.OK, allowed.status)
  }

  @Test
  fun beforeReady_answers503() = testApplication {
    val server = KetchServer(createTestKetch(), ready = false)
    application { with(server) { configureServer() } }

    assertEquals(HttpStatusCode.ServiceUnavailable, client.get("/api/connections").status)
    assertEquals(
      HttpStatusCode.ServiceUnavailable,
      client.get("/api/connections/events").status,
    )
  }

  @Test
  fun events_sendSnapshotsThenAnErrorWhenTheEngineFails() = testApplication {
    val ketch = scripted {
      flow {
        emit(snapshot(1))
        emit(snapshot(2))
        throw IllegalStateException("sampler failed")
      }
    }
    application { with(createTestServer(ketch)) { configureServer() } }

    val response = client.get("/api/connections/events?limit=8")
    val body = response.bodyAsText()

    assertEquals(HttpStatusCode.OK, response.status)
    assertEquals(listOf("snapshot", "snapshot", "error"), eventNames(body))
    val ids = Regex("id: ?(\\d+)").findAll(body).map { it.groupValues[1] }.toList()
    assertEquals(listOf("1", "2"), ids)
    assertTrue("\"total\":2" in body, body)
    assertTrue("internal_error" in body, body)
    // The engine's reason stays in the server's log.
    assertTrue("sampler failed" !in body, body)
    assertEquals(listOf(8), ketch.limits)
  }

  @Test
  fun events_unsupported_sendAnUnsupportedError() = testApplication {
    application { with(createTestServer(Unsupported(createTestKetch()))) { configureServer() } }

    val body = client.get("/api/connections/events").bodyAsText()

    assertEquals(listOf("error"), eventNames(body))
    assertTrue("\"error\":\"unsupported\"" in body, body)
  }

  @Test
  fun events_whileIdle_repeatTheLastSnapshot() = runTest {
    val ketch = scripted {
      flow {
        emit(snapshot(1))
        awaitCancellation()
      }
    }
    withLiveServer(ketch, ConnectionStreams(keepAlive = 20.milliseconds)) { client ->
      client.prepareGet("/api/connections/events").execute { response ->
        val channel = response.bodyAsChannel()
        val events = List(3) { channel.nextEvent() }
        assertEquals(listOf("snapshot", "snapshot", "snapshot"), events.map { it.first })
        assertEquals(1, events.map { it.second }.distinct().size)
      }
    }
  }

  @Test
  fun events_pastTheCap_answer429UntilAStreamCloses() = runTest {
    val ketch = scripted {
      flow {
        emit(snapshot(1))
        awaitCancellation()
      }
    }
    val streams = ConnectionStreams(max = 1, keepAlive = 20.milliseconds)
    withLiveServer(ketch, streams) { client ->
      val opened = CompletableDeferred<Unit>()
      val release = CompletableDeferred<Unit>()
      coroutineScope {
        launch {
          client.prepareGet("/api/connections/events").execute { response ->
            response.bodyAsChannel().nextEvent()
            opened.complete(Unit)
            release.await()
          }
        }
        opened.await()
        val refused = client.get("/api/connections/events")

        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        assertEquals("too_many_streams", refused.error().error)
        // A snapshot is not a stream, so it is still answered.
        assertEquals(HttpStatusCode.OK, client.get("/api/connections").status)
        release.complete(Unit)
      }
      // The server notices the closed stream at its next write and gives its slot back.
      while (true) {
        val admitted = client.prepareGet("/api/connections/events").execute { response ->
          if (response.status != HttpStatusCode.OK) return@execute false
          assertEquals("snapshot", response.bodyAsChannel().nextEvent().first)
          true
        }
        if (admitted) break
        delay(20)
      }
    }
  }

  @Test
  fun streams_giveBackTheirSlots() {
    val streams = ConnectionStreams(max = 2)

    assertTrue(streams.tryOpen())
    assertTrue(streams.tryOpen())
    assertTrue(streams.isFull)
    assertEquals(false, streams.tryOpen())
    streams.close()
    assertEquals(false, streams.isFull)
    assertTrue(streams.tryOpen())
  }
}
