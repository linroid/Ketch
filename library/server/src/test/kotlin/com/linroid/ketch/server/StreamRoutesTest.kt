package com.linroid.ketch.server

import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStats
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.TasksResponse
import com.linroid.ketch.endpoints.stream.InterestUpdate
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamHello
import com.linroid.ketch.endpoints.stream.StreamInterest
import com.linroid.ketch.endpoints.stream.StreamSubscribed
import com.linroid.ketch.endpoints.stream.StreamSubscription
import com.linroid.ketch.server.api.ServerJson
import com.linroid.ketch.server.api.streamRoutes
import com.linroid.ketch.server.stream.EventHub
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO as ClientCIO
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** `GET /api/stream` and its subscription, on a real socket. */
class StreamRoutesTest {

  /**
   * Serves only the stream routes over a hub of [ketch], on a real socket: the test engine holds
   * a streamed response back until it ends.
   */
  private fun withLiveServer(
    ketch: KetchApi = createTestKetch(),
    maxStreams: Int = 4,
    heartbeat: Duration = 15.seconds,
    block: suspend (HttpClient, EventHub) -> Unit,
  ) = runBlocking(Dispatchers.Default) {
    withTimeout(20_000) {
      val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
      val hub = EventHub(ketch, scope, maxStreams = maxStreams)
      val server = embeddedServer(ServerCIO, host = "127.0.0.1", port = 0) {
        install(SSE)
        install(Resources)
        install(ContentNegotiation) { json(ServerJson) }
        routing { streamRoutes(hub, heartbeat) }
      }
      server.start(wait = false)
      val port = server.engine.resolvedConnectors().first().port
      val client = HttpClient(ClientCIO) {
        defaultRequest { url("http://127.0.0.1:$port") }
      }
      try {
        block(client, hub)
      } finally {
        client.close()
        server.stop(gracePeriodMillis = 0, timeoutMillis = 1_000)
        scope.cancel()
      }
    }
  }

  @Test
  fun stream_startsWithHelloAndSnapshot_thenFollowsItsSubscription() =
    withLiveServer { client, _ -> streamFollowsItsSubscription(client) }

  private suspend fun streamFollowsItsSubscription(client: HttpClient) {
    client.prepareGet("/api/stream").execute { response ->
      assertEquals(HttpStatusCode.OK, response.status)
      assertEquals("no", response.headers["X-Accel-Buffering"])
      val channel = response.bodyAsChannel()
      val (helloName, helloData) = channel.nextEvent()
      assertEquals(StreamEvents.HELLO, helloName)
      val hello = ServerJson.decodeFromString<StreamHello>(helloData)
      assertEquals(StreamEvents.PROTOCOL, hello.protocol)
      assertEquals(StreamEvents.SUBSCRIBED, channel.nextEvent().first)
      val (tasksName, tasksData) = channel.nextEvent()
      assertEquals(StreamEvents.TASKS, tasksName)
      assertEquals(emptyList(), ServerJson.decodeFromString<TasksResponse>(tasksData).tasks)

      val stats = StreamInterest(7, Interest.Stats, 300)
      val answer = client.subscribe(
        hello.streamId,
        StreamSubscription(revision = 1, interests = listOf(stats)),
      )
      assertEquals(HttpStatusCode.OK, answer.status)
      val applied = ServerJson.decodeFromString<StreamSubscribed>(answer.bodyAsText())
      assertEquals(listOf(stats), applied.subscription.interests)

      assertEquals(StreamEvents.SUBSCRIBED, channel.nextEvent().first)
      val (statsName, statsData) = channel.nextEvent()
      assertEquals(StreamEvents.INTEREST, statsName)
      val update = ServerJson.decodeFromString<InterestUpdate>(statsData)
      assertEquals(7, update.id)
      assertEquals(
        KetchStats(),
        ServerJson.decodeFromJsonElement(Interest.Stats.valueSerializer, update.value),
      )
    }
  }

  @Test
  fun subscription_unknownStreamOrBeyondLimits_isRefused() = withLiveServer { client, _ ->
    val unknown = client.subscribe("nope", StreamSubscription(revision = 1))
    assertEquals(HttpStatusCode.NotFound, unknown.status)
    assertEquals(StreamEvents.UNKNOWN_STREAM, unknown.error().error)

    client.prepareGet("/api/stream").execute { response ->
      val channel = response.bodyAsChannel()
      val streamId = ServerJson.decodeFromString<StreamHello>(channel.nextEvent().second).streamId
      val invalid = client.subscribe(
        streamId,
        StreamSubscription(revision = 1, progressIntervalMs = 0),
      )
      assertEquals(HttpStatusCode.BadRequest, invalid.status)
      assertEquals(StreamEvents.INVALID_SUBSCRIPTION, invalid.error().error)
    }
  }

  @Test
  fun stream_beyondMaxStreams_answers429_andAClosedClientFreesItsStream() =
    withLiveServer(maxStreams = 1, heartbeat = 100.milliseconds) { client, hub ->
      client.prepareGet("/api/stream").execute { response ->
        response.bodyAsChannel().nextEvent()
        val refused = client.get("/api/stream")
        assertEquals(HttpStatusCode.TooManyRequests, refused.status)
        assertEquals(StreamEvents.TOO_MANY_STREAMS, refused.error().error)
      }
      // Leaving the response closes the connection; the server notices on its next write, at
      // the latest the heartbeat.
      withTimeout(5_000) {
        while (hub.openStreams > 0) delay(20)
      }
      client.prepareGet("/api/stream").execute { response ->
        assertEquals(HttpStatusCode.OK, response.status)
      }
    }

  @Test
  fun idleStream_sendsHeartbeats() = withLiveServer(heartbeat = 100.milliseconds) { client, _ ->
    client.prepareGet("/api/stream").execute { response ->
      val channel = response.bodyAsChannel()
      withTimeout(5_000) {
        while (true) {
          val line = channel.readUTF8Line() ?: error("The stream ended")
          if (line.startsWith(":")) break
        }
      }
    }
  }

  @Test
  fun status_listsTheEventStream() = testApplication {
    application { with(createTestServer()) { configureServer() } }

    val status = ServerJson.decodeFromString<KetchStatus>(client.get("/api/status").bodyAsText())

    assertTrue(StreamEvents.FEATURE in status.features)
  }

  @Test
  fun withToken_refusesStreamsWithoutIt() = testApplication {
    application {
      with(KetchServer(createTestKetch(), apiToken = "secret-token")) { configureServer() }
    }

    assertEquals(HttpStatusCode.Unauthorized, client.get("/api/stream").status)
    val subscription = client.put("/api/stream/any/subscription") {
      contentType(ContentType.Application.Json)
      setBody("{}")
    }
    assertEquals(HttpStatusCode.Unauthorized, subscription.status)
  }

  private suspend fun HttpClient.subscribe(
    streamId: String,
    subscription: StreamSubscription,
  ): HttpResponse = put("/api/stream/$streamId/subscription") {
    contentType(ContentType.Application.Json)
    header(HttpHeaders.Accept, ContentType.Application.Json.toString())
    setBody(ServerJson.encodeToString(subscription))
  }

  private suspend fun HttpResponse.error(): ErrorResponse =
    ServerJson.decodeFromString(bodyAsText())

  /** Reads the next event of an SSE [channel] as its name and data, skipping comments. */
  private suspend fun ByteReadChannel.nextEvent(): Pair<String, String> =
    withContext(Dispatchers.Default) {
      withTimeout(5_000) { readEvent() }
    }

  private suspend fun ByteReadChannel.readEvent(): Pair<String, String> {
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
}
