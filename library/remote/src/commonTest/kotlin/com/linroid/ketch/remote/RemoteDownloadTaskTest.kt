package com.linroid.ketch.remote

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.endpoints.model.TaskSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.resources.Resources
import io.ktor.http.HttpHeaders
import io.ktor.http.headersOf
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
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
        httpClient = client,
        onRemoved = {},
      )

      task.setSpeedLimit(updated.speedLimit)

      assertEquals(updated, task.requestState.value)
      assertEquals(updated, task.request)
      assertEquals(segments, task.segments.value)

      val completed = listOf(Segment(0, 0, 99, 100))
      task.updateState(task.state.value, updated.copy(speedLimit = SpeedLimit.Unlimited), completed)
      assertEquals(SpeedLimit.Unlimited, task.requestState.value.speedLimit)
      assertEquals(completed, task.segments.value)

      // Events from older servers omit settings and segments; preserve the latest values.
      task.updateState(task.state.value)
      assertEquals(SpeedLimit.Unlimited, task.request.speedLimit)
      assertEquals(completed, task.segments.value)
    } finally {
      client.close()
    }
  }
}
