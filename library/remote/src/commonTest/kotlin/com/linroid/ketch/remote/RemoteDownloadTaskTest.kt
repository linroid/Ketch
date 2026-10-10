package com.linroid.ketch.remote

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.FileSelectionRequest
import com.linroid.ketch.endpoints.model.TaskSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.MockRequestHandler
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.resources.Resources
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.TextContent
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFails
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
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

  @Test
  fun selectFiles_oldServer404_isUnsupported() = runTest {
    for (status in listOf(HttpStatusCode.NotFound, HttpStatusCode.MethodNotAllowed)) {
      val client = client { respond(content = "", status = status) }
      try {
        val task = task(client)
        assertFailsWith<UnsupportedOperationException> { task.selectFiles(setOf("0")) }
        assertEquals(emptySet(), task.request.selectedFileIds)
      } finally {
        client.close()
      }
    }
    // A missing task is a 404 that explains itself.
    val client = client {
      respond(
        content = Json.encodeToString(ErrorResponse("not_found", "Task not found: task")),
        status = HttpStatusCode.NotFound,
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    try {
      val error = assertFailsWith<RemoteApiException> { task(client).selectFiles(setOf("0")) }
      assertEquals("not_found", error.errorCode)
    } finally {
      client.close()
    }
  }

  @Test
  fun selectFiles_invalidSelection_throwsIllegalArgument() = runTest {
    val responses = listOf(
      HttpStatusCode.BadRequest to ErrorResponse("invalid_selection", "Unknown file id"),
      HttpStatusCode.Conflict to ErrorResponse("selection_unavailable", "Not known yet"),
      HttpStatusCode.NotImplemented to ErrorResponse("unsupported", "No files to choose"),
    )
    for ((status, body) in responses) {
      val client = client {
        respond(
          content = Json.encodeToString(body),
          status = status,
          headers = headersOf(HttpHeaders.ContentType, "application/json"),
        )
      }
      try {
        val task = task(client)
        val error = assertFails { task.selectFiles(setOf("9")) }
        when (status) {
          HttpStatusCode.BadRequest -> assertIs<IllegalArgumentException>(error)
          HttpStatusCode.Conflict -> assertEquals(
            "selection_unavailable", assertIs<RemoteApiException>(error).errorCode,
          )
          else -> assertIs<UnsupportedOperationException>(error)
        }
        assertEquals(body.message, error.message)
      } finally {
        client.close()
      }
    }
    // Arguments are checked before anything is sent.
    val client = client { error("Nothing must be sent") }
    try {
      assertFailsWith<IllegalArgumentException> { task(client).selectFiles(emptySet()) }
      assertFailsWith<IllegalArgumentException> {
        task(client).selectFiles(setOf("x".repeat(129)))
      }
    } finally {
      client.close()
    }
  }

  @Test
  fun selectFiles_success_appliesTheSnapshot() = runTest {
    val request = DownloadRequest(url = "magnet:?xt=urn:btih:00", selectedFileIds = setOf("0"))
    val snapshot = TaskSnapshot(
      taskId = "task",
      request = request.copy(selectedFileIds = setOf("0", "2")),
      state = DownloadState.Paused(DownloadProgress(10, 40)),
      segments = listOf(Segment(0, 0, 9, 10), Segment(2, 10, 39, 0)),
      createdAt = Instant.fromEpochMilliseconds(0),
    )
    var sent: String? = null
    val client = client { call ->
      assertEquals(HttpMethod.Put, call.method)
      assertEquals("/api/tasks/task/files", call.url.encodedPath)
      sent = (call.body as TextContent).text
      respond(
        content = remoteJson.encodeToString(snapshot),
        headers = headersOf(HttpHeaders.ContentType, "application/json"),
      )
    }
    try {
      val task = task(client, request)

      task.selectFiles(setOf("0", "2"))

      assertEquals(
        FileSelectionRequest(setOf("0", "2")),
        remoteJson.decodeFromString<FileSelectionRequest>(assertNotNull(sent)),
      )
      assertEquals(setOf("0", "2"), task.request.selectedFileIds)
      assertEquals(snapshot.state, task.state.value)
      assertEquals(snapshot.segments, task.segments.value)
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
