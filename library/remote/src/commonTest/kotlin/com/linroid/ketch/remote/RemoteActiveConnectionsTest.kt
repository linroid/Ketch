package com.linroid.ketch.remote

import com.linroid.ketch.api.ActiveConnection
import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.PeerDetails
import com.linroid.ketch.api.SystemInfo
import com.linroid.ketch.endpoints.model.ErrorResponse
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandleScope
import io.ktor.client.engine.mock.respond
import io.ktor.client.request.HttpRequestData
import io.ktor.client.request.HttpResponseData
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.time.Instant

class RemoteActiveConnectionsTest {
  private val json = Json { encodeDefaults = true }

  private fun snapshot(total: Int) = ActiveConnections(
    sampledAt = Instant.fromEpochMilliseconds(1_000L + total),
    connections = List(total) { index ->
      ActiveConnection(
        id = index.toLong(),
        taskId = "t1",
        source = "torrent",
        protocol = "BitTorrent",
        secure = false,
        direction = ConnectionDirection.INCOMING,
        host = "203.0.113.7",
        port = 6881,
        uploadBps = 2_048,
        openedAt = Instant.fromEpochMilliseconds(500),
        peer = PeerDetails(
          wire = "v2",
          peerChoking = true,
          uploadSlot = true,
          peerInterested = true,
        ),
      )
    },
    total = total,
    uploadBps = 2_048L * total,
  )

  private class Fixture(val remote: RemoteKetch, val requests: MutableList<HttpRequestData>) {
    /** The requests other than the status. */
    val streamRequests get() = requests.filter { it.url.encodedPath != "/api/status" }
  }

  private fun fixture(
    features: Set<String> = setOf(KetchFeatures.ACTIVE_CONNECTIONS),
    stream: MockRequestHandleScope.(HttpRequestData) -> HttpResponseData,
  ): Fixture {
    val requests = mutableListOf<HttpRequestData>()
    val remote = RemoteKetch("remote.example", 8642, null, false, MockEngine { request ->
      requests += request
      if (request.url.encodedPath == "/api/status") {
        respond(
          json.encodeToString(status(features)),
          HttpStatusCode.OK,
          headersOf(HttpHeaders.ContentType, "application/json"),
        )
      } else {
        stream(request)
      }
    })
    return Fixture(remote, requests)
  }

  private fun MockRequestHandleScope.events(vararg lines: String) = respond(
    content = lines.joinToString(""),
    headers = headersOf(HttpHeaders.ContentType, "text/event-stream"),
  )

  private fun snapshotEvent(snapshot: ActiveConnections, id: Int) =
    "event: snapshot\r\ndata: ${json.encodeToString(snapshot)}\r\nid: $id\r\n\r\n"

  private fun errorEvent(error: String) =
    "event: error\ndata: ${json.encodeToString(ErrorResponse(error, "Refused"))}\n\n"

  private fun MockRequestHandleScope.error(status: HttpStatusCode, error: String) = respond(
    json.encodeToString(ErrorResponse(error, "Refused")),
    status,
    headersOf(HttpHeaders.ContentType, "application/json"),
  )

  /** Collects [RemoteKetch.activeConnections] until it fails, returning what came and why. */
  private suspend fun RemoteKetch.drain(limit: Int): Pair<List<ActiveConnections>, Throwable?> {
    var failure: Throwable? = null
    val snapshots = activeConnections(limit).catch { failure = it }.toList()
    return snapshots to failure
  }

  @Test
  fun limitOutOfRange_isRefusedBeforeSending() = runTest {
    val fixture = fixture { events() }
    try {
      assertFailsWith<IllegalArgumentException> { fixture.remote.activeConnections(0) }
      assertFailsWith<IllegalArgumentException> {
        fixture.remote.activeConnections(ActiveConnections.MAX_LIMIT + 1)
      }
      assertEquals(emptyList(), fixture.requests)
    } finally {
      fixture.remote.close()
    }
  }

  @Test
  fun serverWithoutFeature_failsWithoutAskingForTheStream() = runTest {
    val fixture = fixture(features = emptySet()) { events(snapshotEvent(snapshot(1), 1)) }
    try {
      val (snapshots, failure) = fixture.remote.drain(limit = 10)

      assertEquals(emptyList(), snapshots)
      assertIs<UnsupportedOperationException>(failure)
      assertEquals(emptyList(), fixture.streamRequests)
    } finally {
      fixture.remote.close()
    }
  }

  @Test
  fun stream_deliversSnapshotsWithoutRepeatsThenFailsWhenItEnds() = runTest {
    val fixture = fixture {
      events(
        snapshotEvent(snapshot(1), 1),
        ": keep reading\n\n",
        snapshotEvent(snapshot(1), 2),
        snapshotEvent(snapshot(2), 3),
      )
    }
    try {
      val (snapshots, failure) = fixture.remote.drain(limit = 7)

      assertEquals(listOf(snapshot(1), snapshot(2)), snapshots)
      assertIs<IllegalStateException>(failure)
      val request = fixture.streamRequests.single()
      assertEquals("/api/connections/events", request.url.encodedPath)
      assertEquals("7", request.url.parameters["limit"])
    } finally {
      fixture.remote.close()
    }
  }

  @Test
  fun stream_errorEvent_endsTheFlowAfterEarlierSnapshots() = runTest {
    val unsupported = fixture {
      events(snapshotEvent(snapshot(1), 1), errorEvent("unsupported"))
    }
    val busy = fixture { events(errorEvent("too_many_streams")) }
    try {
      val (snapshots, failure) = unsupported.remote.drain(limit = 1)
      assertEquals(listOf(snapshot(1)), snapshots)
      assertIs<UnsupportedOperationException>(failure)

      val refused = assertIs<RemoteApiException>(busy.remote.drain(limit = 1).second)
      assertEquals(429, refused.status)
      assertEquals("too_many_streams", refused.errorCode)
    } finally {
      unsupported.remote.close()
      busy.remote.close()
    }
  }

  @Test
  fun stream_withoutTheRoute_isUnsupported() = runTest {
    for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed)) {
      val fixture = fixture { respond("", status) }
      try {
        assertIs<UnsupportedOperationException>(fixture.remote.drain(limit = 1).second)
      } finally {
        fixture.remote.close()
      }
    }
    val unimplemented = fixture { error(HttpStatusCode.NotImplemented, "unsupported") }
    try {
      assertIs<UnsupportedOperationException>(unimplemented.remote.drain(limit = 1).second)
    } finally {
      unimplemented.remote.close()
    }
  }

  @Test
  fun stream_refused_failsWithTheServersReason() = runTest {
    val busy = fixture { error(HttpStatusCode.TooManyRequests, "too_many_streams") }
    val lost = fixture { error(HttpStatusCode.NotFound, "not_found") }
    try {
      val refused = assertIs<RemoteApiException>(busy.remote.drain(limit = 1).second)
      assertEquals(429, refused.status)
      assertEquals("too_many_streams", refused.errorCode)
      // A 404 that explains itself is not a missing route.
      assertIs<RemoteApiException>(lost.remote.drain(limit = 1).second)
    } finally {
      busy.remote.close()
      lost.remote.close()
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
