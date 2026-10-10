package com.linroid.ketch.remote

import com.linroid.ketch.api.torrent.TorrentActivity
import com.linroid.ketch.api.torrent.TorrentCapabilities
import com.linroid.ketch.api.torrent.TorrentCommandContext
import com.linroid.ketch.api.torrent.TorrentCommandError
import com.linroid.ketch.api.torrent.TorrentCommandException
import com.linroid.ketch.api.torrent.TorrentCommandResult
import com.linroid.ketch.api.torrent.TorrentFileOrder
import com.linroid.ketch.api.torrent.TorrentFilePage
import com.linroid.ketch.api.torrent.TorrentPageRequest
import com.linroid.ketch.api.torrent.TorrentRevision
import com.linroid.ketch.api.torrent.TorrentSnapshot
import com.linroid.ketch.endpoints.model.ErrorResponse
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.resources.Resources
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

class RemoteTorrentControllerTest {
  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  private val full = TorrentCapabilities(
    names = setOf("inspect", "file-selection", "seeding"),
    maxPageSize = 1000,
  )

  private val revision = TorrentRevision("epoch", 4)

  private fun snapshot(sequence: Long, epoch: String = "epoch") = TorrentSnapshot(
    taskId = "t1",
    revision = TorrentRevision(epoch, sequence),
    activity = TorrentActivity.DOWNLOADING,
    selectionGeneration = 0,
    selectionComplete = false,
  )

  private class Fixture(
    val controller: RemoteTorrentController,
    val generation: MutableStateFlow<Long>,
    val paths: MutableList<String>,
    val client: HttpClient,
  )

  private fun fixture(
    handler: suspend MockRequestHandleScope.(HttpRequestData, MutableStateFlow<Long>) ->
    HttpResponseData,
  ): Fixture {
    val generation = MutableStateFlow(0L)
    val paths = mutableListOf<String>()
    val client = HttpClient(MockEngine { request ->
      paths += request.url.encodedPath
      handler(request, generation)
    }) {
      install(Resources)
      install(SSE)
      install(ContentNegotiation) { json(json) }
    }
    return Fixture(RemoteTorrentController(client, generation, json), generation, paths, client)
  }

  private fun MockRequestHandleScope.json(
    body: String,
    status: HttpStatusCode = HttpStatusCode.OK,
  ) = respond(body, status, headersOf(HttpHeaders.ContentType, "application/json"))

  private fun MockRequestHandleScope.events(vararg events: Pair<String, String>) = respond(
    content = events.joinToString("") { (name, data) -> "event: $name\ndata: $data\n\n" },
    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
  )

  private fun MockRequestHandleScope.capabilities(
    request: HttpRequestData,
    capabilities: TorrentCapabilities = full,
  ): HttpResponseData? = if (request.url.encodedPath == "/api/torrents/capabilities") {
    json(json.encodeToString(capabilities))
  } else {
    null
  }

  private suspend fun failure(
    error: TorrentCommandError,
    block: suspend () -> Unit,
  ): TorrentCommandException {
    val e = assertFailsWith<TorrentCommandException> { block() }
    assertEquals(error, e.error)
    return e
  }

  @Test
  fun capabilities_oldServer_failsClosed() = runTest {
    val fixture = fixture { _, _ -> respond("", HttpStatusCode.NotFound) }
    try {
      val controller = fixture.controller
      assertEquals(emptySet(), controller.capabilities().names)

      failure(TorrentCommandError.UNSUPPORTED) {
        controller.select("t1", setOf("0"), TorrentCommandContext("k", revision))
      }
      failure(TorrentCommandError.UNSUPPORTED) {
        controller.setSeeding("t1", true, TorrentCommandContext("k", revision))
      }
      failure(TorrentCommandError.UNSUPPORTED) { controller.snapshot("t1") }
      failure(TorrentCommandError.UNSUPPORTED) { controller.observe("t1").toList() }

      // Asked once for this connection; nothing else was sent.
      assertEquals(listOf("/api/torrents/capabilities"), fixture.paths)
    } finally {
      fixture.client.close()
    }
  }

  @Test
  fun select_unsupported_sendsNoRequest() = runTest {
    val fixture = fixture { request, _ ->
      capabilities(request, TorrentCapabilities(names = setOf("inspect")))
        ?: error("Unexpected request")
    }
    try {
      failure(TorrentCommandError.UNSUPPORTED) {
        fixture.controller.select("t1", setOf("0"), TorrentCommandContext("k", revision))
      }
      assertEquals(listOf("/api/torrents/capabilities"), fixture.paths)
    } finally {
      fixture.client.close()
    }
  }

  @Test
  fun select_conflict_mapsToCommandException() = runTest {
    val current = TorrentRevision("epoch", 9)
    val fixture = fixture { request, _ ->
      capabilities(request) ?: json(
        json.encodeToString(ErrorResponse("revision_conflict", "Changed", current)),
        HttpStatusCode.Conflict,
      )
    }
    try {
      val error = failure(TorrentCommandError.CONFLICT) {
        fixture.controller.select("t1", setOf("0"), TorrentCommandContext("k", revision))
      }
      assertEquals(current, error.currentRevision)
      assertEquals("Changed", error.message)
      assertEquals(
        listOf("/api/torrents/capabilities", "/api/torrents/t1/selection"),
        fixture.paths,
      )
    } finally {
      fixture.client.close()
    }
  }

  @Test
  fun select_success_returnsTheResult() = runTest {
    val result = TorrentCommandResult("t1", TorrentRevision("epoch", 5), 1)
    val fixture = fixture { request, _ ->
      capabilities(request) ?: json(json.encodeToString(result))
    }
    try {
      assertEquals(
        result,
        fixture.controller.select("t1", setOf("0"), TorrentCommandContext("k", revision)),
      )
    } finally {
      fixture.client.close()
    }
  }

  @Test
  fun select_responseAfterReconnect_isConnectionChanged() = runTest {
    val result = TorrentCommandResult("t1", TorrentRevision("epoch", 5), 1)
    val fixture = fixture { request, generation ->
      capabilities(request) ?: run {
        // The event stream reconnected while the command was in flight.
        generation.update { it + 1 }
        json(json.encodeToString(result))
      }
    }
    try {
      failure(TorrentCommandError.CONNECTION_CHANGED) {
        fixture.controller.select("t1", setOf("0"), TorrentCommandContext("k", revision))
      }
      // A new connection asks for the capabilities again.
      fixture.paths.clear()
      fixture.controller.capabilities()
      assertEquals(listOf("/api/torrents/capabilities"), fixture.paths)
    } finally {
      fixture.client.close()
    }
  }

  @Test
  fun snapshotAndFiles_notFound_areNull() = runTest {
    val fixture = fixture { request, _ ->
      capabilities(request) ?: json(
        json.encodeToString(ErrorResponse("not_found", "No torrent download")),
        HttpStatusCode.NotFound,
      )
    }
    try {
      assertNull(fixture.controller.snapshot("t1"))
      assertNull(fixture.controller.files("t1"))
    } finally {
      fixture.client.close()
    }
  }

  @Test
  fun files_sendOrderAndCursor() = runTest {
    val page = TorrentFilePage("t1", revision, 0, 0, emptyList())
    val queries = mutableListOf<String>()
    val fixture = fixture { request, _ ->
      capabilities(request) ?: run {
        queries += request.url.encodedQuery
        json(json.encodeToString(page))
      }
    }
    try {
      fixture.controller.files("t1", TorrentPageRequest(50, "c1.x"), TorrentFileOrder.NAME, true)
      fixture.controller.files("t1")

      assertEquals(listOf("limit=50&cursor=c1.x&sort=name&desc=true", "limit=100"), queries)
    } finally {
      fixture.client.close()
    }
  }

  @Test
  fun observe_olderSnapshot_isIgnored() = runTest {
    val fixture = fixture { request, _ ->
      capabilities(request) ?: events(
        "snapshot" to json.encodeToString(snapshot(5)),
        "snapshot" to json.encodeToString(snapshot(4)),
        "snapshot" to json.encodeToString(snapshot(5)),
        "snapshot" to json.encodeToString(snapshot(6)),
        "removed" to """{"taskId":"t1"}""",
      )
    }
    try {
      val received = fixture.controller.observe("t1").toList()

      assertEquals(listOf(5L, 6L), received.filterNotNull().map { it.revision.sequence })
      assertNull(received.last())
      assertEquals("/api/torrents/t1/events", fixture.paths.last())
    } finally {
      fixture.client.close()
    }
  }

  @Test
  fun observe_removed_completes() = runTest {
    val fixture = fixture { request, _ ->
      capabilities(request) ?: events(
        "snapshot" to json.encodeToString(snapshot(1)),
        "removed" to """{"taskId":"t1"}""",
        "snapshot" to json.encodeToString(snapshot(2)),
      )
    }
    try {
      assertEquals(listOf(snapshot(1), null), fixture.controller.observe("t1").toList())
    } finally {
      fixture.client.close()
    }
  }

  @Test
  fun observe_errorEvent_failsWithItsCode() = runTest {
    val fixture = fixture { request, _ ->
      capabilities(request) ?: events(
        "error" to json.encodeToString(ErrorResponse("resource_exhausted", "Too many")),
      )
    }
    try {
      failure(TorrentCommandError.RESOURCE_EXHAUSTED) {
        fixture.controller.observe("t1").toList()
      }
    } finally {
      fixture.client.close()
    }
  }

  @Test
  fun observe_transportFailure_terminates() = runTest {
    // The stream ends without saying why, as when the connection drops.
    val dropped = fixture { request, _ ->
      capabilities(request) ?: events("snapshot" to json.encodeToString(snapshot(1)))
    }
    // The server restarted: a new epoch needs a new subscription.
    val restarted = fixture { request, _ ->
      capabilities(request) ?: events(
        "snapshot" to json.encodeToString(snapshot(3)),
        "snapshot" to json.encodeToString(snapshot(1, epoch = "other")),
        "removed" to """{"taskId":"t1"}""",
      )
    }
    try {
      for (fixture in listOf(dropped, restarted)) {
        val received = mutableListOf<TorrentSnapshot?>()
        failure(TorrentCommandError.CONNECTION_CHANGED) {
          fixture.controller.observe("t1").collect { received += it }
        }
        assertEquals(1, received.size, if (fixture === dropped) "dropped" else "restarted")
      }
    } finally {
      dropped.client.close()
      restarted.client.close()
    }
  }
}
