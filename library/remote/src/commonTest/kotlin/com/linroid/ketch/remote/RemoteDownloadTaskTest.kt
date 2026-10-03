package com.linroid.ketch.remote

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.TaskSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.resources.Resources
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.time.Instant

class RemoteDownloadTaskTest {
  @Test
  fun setSpeedLimit_response_updatesRequestAndSegmentsWithoutStateChange() = runTest {
    val original = DownloadRequest(url = "https://example.com/file")
    val updated = original.copy(speedLimit = SpeedLimit.of(1024), connections = 1)
    val segments = listOf(Segment(0, 0, 99, 50))
    val now = Instant.fromEpochMilliseconds(0)
    val snapshot = TaskSnapshot("task", updated, DownloadState.Queued, segments, now)
    val client = HttpClient(MockEngine {
      respond(
        content = Json.encodeToString(snapshot),
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }) {
      install(Resources)
      install(ContentNegotiation) { json() }
    }
    try {
      val task = RemoteDownloadTask(
        taskId = "task",
        request = original,
        createdAt = now,
        initialState = DownloadState.Queued,
        initialSegments = emptyList(),
        initialQueuePosition = null,
        httpClient = client,
        onRemoved = {},
      )

      task.setSpeedLimit(updated.speedLimit)

      assertEquals(updated, task.requestState.value)
      assertEquals(updated, task.request)
      assertEquals(segments, task.segments.value)

      val completed = listOf(Segment(0, 0, 99, 100))
      task.updateState(
        task.state.value,
        updated.copy(speedLimit = SpeedLimit.Unlimited),
        completed,
        queuePosition = null,
      )
      assertEquals(SpeedLimit.Unlimited, task.requestState.value.speedLimit)
      assertEquals(completed, task.segments.value)

      // Events from older servers omit settings and segments; preserve the latest values.
      task.updateState(task.state.value, request = null, segments = null, queuePosition = null)
      assertEquals(SpeedLimit.Unlimited, task.request.speedLimit)
      assertEquals(completed, task.segments.value)
    } finally {
      client.close()
    }
  }

  @Test
  fun setConnections_autoRejectedByOlderServer_throwsUnsupported() = runTest {
    // What servers that predate Auto answer for 0.
    val body = """{"error":"invalid_connections",""" +
      """"message":"Connections must be greater than 0"}"""
    val client = client {
      respond(
        content = body,
        status = HttpStatusCode.BadRequest,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    try {
      // Not Auto already, so applying Auto despite the error would show.
      val task = task(client, DownloadRequest(url = "https://example.com/file", connections = 4))

      assertFailsWith<UnsupportedOperationException> { task.setConnections(0) }
      assertEquals(4, task.request.connections)
      assertEquals(4, task.requestState.value.connections)
    } finally {
      client.close()
    }
  }

  @Test
  fun setConnections_autoRejectedForAnotherReason_throwsServerMessage() = runTest {
    val client = client {
      respond(
        content = """{"error":"bad_request","message":"Task has finished"}""",
        status = HttpStatusCode.BadRequest,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    try {
      val error = assertFailsWith<RemoteApiException> { task(client).setConnections(0) }
      assertEquals("bad_request", error.errorCode)
      assertEquals("Task has finished", error.message)
    } finally {
      client.close()
    }
  }

  @Test
  fun remove_filesOutsideAllowedFolders_throwsServerMessageAndKeepsTask() = runTest {
    val message = "/mnt/old/file is outside the folders files can be deleted from"
    val client = client {
      respond(
        content = Json.encodeToString(ErrorResponse("path_rejected", message)),
        status = HttpStatusCode.Forbidden,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    try {
      var removed = false
      val task = task(client, onRemoved = { removed = true })

      val error = assertFailsWith<RemoteApiException> { task.remove(deleteFiles = true) }

      assertEquals(403, error.status)
      assertEquals("path_rejected", error.errorCode)
      assertEquals(message, error.message)
      assertFalse(removed)
    } finally {
      client.close()
    }
  }

  @Test
  fun setConnections_snapshotResponse_updatesQueuePosition() = runTest {
    val request = DownloadRequest(url = "https://example.com/file", connections = 4)
    val snapshot = TaskSnapshot(
      taskId = "task",
      request = request.copy(connections = 0),
      state = DownloadState.Queued,
      createdAt = Instant.fromEpochMilliseconds(0),
      queuePosition = 2,
    )
    val client = client {
      respond(
        content = remoteJson.encodeToString(snapshot),
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    try {
      val task = task(client, request)

      task.setConnections(0)

      assertEquals(0, task.request.connections)
      assertEquals(2, task.queuePosition.value)
    } finally {
      client.close()
    }
  }

  @Test
  fun setSpeedLimit_eventDuringRequest_keepsEventQueuePosition() = runTest {
    val request = DownloadRequest(url = "https://example.com/file")
    // The server read position 3 for its response before a task ahead finished.
    val snapshot = TaskSnapshot(
      taskId = "task",
      request = request.copy(speedLimit = SpeedLimit.of(1024)),
      state = DownloadState.Queued,
      createdAt = Instant.fromEpochMilliseconds(0),
      queuePosition = 3,
    )
    lateinit var task: RemoteDownloadTask
    val client = client {
      // SSE reports the move to position 2 before the response arrives.
      task.applyEvent(DownloadState.Queued, request = null, segments = null, queuePosition = 2)
      respond(
        content = remoteJson.encodeToString(snapshot),
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    try {
      task = task(client, request)

      task.setSpeedLimit(SpeedLimit.of(1024))

      assertEquals(2, task.queuePosition.value)
      assertEquals(SpeedLimit.of(1024), task.request.speedLimit)
    } finally {
      client.close()
    }
  }

  private fun client(handler: MockRequestHandler) = HttpClient(MockEngine(handler)) {
    install(Resources)
    install(ContentNegotiation) { json(remoteJson) }
  }

  private fun task(
    client: HttpClient,
    request: DownloadRequest = DownloadRequest(url = "https://example.com/file"),
    onRemoved: suspend (String) -> Unit = {},
  ) = RemoteDownloadTask(
    taskId = "task",
    request = request,
    createdAt = Instant.fromEpochMilliseconds(0),
    initialState = DownloadState.Queued,
    initialSegments = emptyList(),
    initialQueuePosition = null,
    httpClient = client,
    onRemoved = onRemoved,
  )

  // Configured like RemoteKetch's, so responses decode as they do in production.
  private val remoteJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
  }
}
