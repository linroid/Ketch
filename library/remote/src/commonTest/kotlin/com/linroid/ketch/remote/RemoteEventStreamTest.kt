package com.linroid.ketch.remote

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.SystemInfo
import com.linroid.ketch.api.TaskInterest
import com.linroid.ketch.endpoints.model.TasksResponse
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamHello
import com.linroid.ketch.endpoints.stream.StreamSubscribed
import com.linroid.ketch.endpoints.stream.StreamSubscription
import com.linroid.ketch.endpoints.stream.TaskInterval
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.utils.io.ByteChannel
import io.ktor.utils.io.writeStringUtf8
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/** How [RemoteKetch] chooses and reads its server's events. */
class RemoteEventStreamTest {
  private val json = Json { encodeDefaults = true }
  private val jsonHeaders = headersOf(HttpHeaders.ContentType, "application/json")
  private val eventHeaders = headersOf(HttpHeaders.ContentType, "text/event-stream")

  private fun status(features: Set<String>) = KetchStatus(
    name = "remote",
    version = "1",
    revision = "r",
    uptime = 0,
    config = DownloadConfig(),
    system = SystemInfo(
      os = "Linux", arch = "x64", separator = "/", javaVersion = "21", availableProcessors = 4,
      maxMemory = 0, totalMemory = 0, freeMemory = 0, downloadDirectory = "/data",
      totalSpace = 0, freeSpace = 0, usableSpace = 0,
    ),
    features = features,
  )

  private fun event(name: String, data: String) = "event: $name\ndata: $data\n\n"

  @Test
  fun start_olderServer_readsTheTaskEvents() = runTest {
    val paths = Channel<String>(Channel.UNLIMITED)
    val remote = RemoteKetch("remote.example", 8642, null, false, MockEngine { request ->
      paths.send(request.url.encodedPath)
      when (request.url.encodedPath) {
        "/api/status" -> respond(json.encodeToString(status(emptySet())), headers = jsonHeaders)
        "/api/tasks" -> respond(json.encodeToString(TasksResponse(emptyList())), headers = jsonHeaders)
        "/api/stream" -> error("An older server has no event stream")
        // Stays open, so the client does not reconnect.
        else -> respond(ByteChannel(), HttpStatusCode.OK, eventHeaders)
      }
    })
    try {
      withContext(Dispatchers.Default) {
        withTimeout(10.seconds) {
          remote.start()
          // Only older servers' clients fetch the task list; the stream carries it otherwise.
          assertEquals(listOf("/api/status", "/api/tasks"), List(2) { paths.receive() })
        }
      }
    } finally {
      remote.close()
    }
  }

  @Test
  fun start_streamServer_sendsTheMergedSubscriptionAndUpdatesIt() = runTest {
    val subscriptions = Channel<StreamSubscription>(Channel.UNLIMITED)
    val events = ByteChannel()
    val remote = RemoteKetch(
      "remote.example",
      8642,
      null,
      false,
      MockEngine { request ->
        when {
          request.url.encodedPath == "/api/status" -> respond(
            json.encodeToString(status(setOf(StreamEvents.FEATURE))),
            headers = jsonHeaders,
          )
          request.url.encodedPath == "/api/stream" ->
            respond(events, HttpStatusCode.OK, eventHeaders)
          request.method == HttpMethod.Put -> {
            assertEquals("/api/stream/s1/subscription", request.url.encodedPath)
            val subscription = request.subscription()
            subscriptions.send(subscription)
            respond(
              json.encodeToString(StreamSubscribed(subscription.revision, subscription)),
              headers = jsonHeaders,
            )
          }
          else -> error("Unexpected request ${request.url.encodedPath}")
        }
      },
      progressInterval = 10.seconds,
    )
    try {
      withContext(Dispatchers.Default) {
        withTimeout(10.seconds) {
          remote.start()
          events.writeStringUtf8(
            event(
              StreamEvents.HELLO,
              json.encodeToString(StreamHello("s1", StreamEvents.PROTOCOL, emptyList())),
            ) + event(StreamEvents.TASKS, json.encodeToString(TasksResponse(emptyList()))),
          )
          events.flush()

          val first = subscriptions.receive()
          assertEquals(1, first.revision)
          assertEquals(listOf(TaskInterval(10_000, null)), first.progress)

          val watching = launch {
            remote.watch(TaskInterest(setOf("a"), progressInterval = 300.milliseconds))
          }
          val second = subscriptions.receive()
          assertEquals(2, second.revision)
          assertEquals(
            listOf(TaskInterval(500, listOf("a")), TaskInterval(10_000, null)),
            second.progress,
          )

          watching.cancel()
          assertEquals(listOf(TaskInterval(10_000, null)), subscriptions.receive().progress)
          assertTrue(remote.connectionState.value == ConnectionState.Connected)
        }
      }
    } finally {
      remote.close()
    }
  }

  private fun HttpRequestData.subscription(): StreamSubscription =
    json.decodeFromString((body as TextContent).text)
}
