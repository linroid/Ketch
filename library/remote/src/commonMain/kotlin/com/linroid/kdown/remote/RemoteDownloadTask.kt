package com.linroid.kdown.remote

import com.linroid.kdown.api.DownloadCondition
import com.linroid.kdown.api.DownloadPriority
import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.DownloadSchedule
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.DownloadTask
import com.linroid.kdown.api.KDownError
import com.linroid.kdown.api.Segment
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.endpoints.Api
import com.linroid.kdown.endpoints.model.PriorityRequest
import com.linroid.kdown.endpoints.model.SpeedLimitRequest
import com.linroid.kdown.endpoints.model.TaskResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.resources.delete
import io.ktor.client.plugins.resources.post
import io.ktor.client.plugins.resources.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.time.Instant

internal class RemoteDownloadTask(
  override val taskId: String,
  override val request: DownloadRequest,
  override val createdAt: Instant,
  initialState: DownloadState,
  initialSegments: List<Segment>,
  private val httpClient: HttpClient,
  private val json: Json,
  private val onRemoved: (String) -> Unit
) : DownloadTask {

  private val _state = MutableStateFlow(initialState)
  override val state: StateFlow<DownloadState> = _state.asStateFlow()

  private val _segments = MutableStateFlow(initialSegments)
  override val segments: StateFlow<List<Segment>> =
    _segments.asStateFlow()

  internal fun updateState(newState: DownloadState) {
    _state.value = newState
  }

  internal fun updateSegments(newSegments: List<Segment>) {
    _segments.value = newSegments
  }

  private val byId get() = Api.Tasks.ById(id = taskId)

  override suspend fun pause() {
    val response = httpClient.post(
      Api.Tasks.ById.Pause(parent = byId)
    )
    checkSuccess(response)
    applyWireResponse(response.bodyAsText())
  }

  override suspend fun resume() {
    val response = httpClient.post(
      Api.Tasks.ById.Resume(parent = byId)
    )
    checkSuccess(response)
    applyWireResponse(response.bodyAsText())
  }

  override suspend fun cancel() {
    val response = httpClient.post(
      Api.Tasks.ById.Cancel(parent = byId)
    )
    checkSuccess(response)
    applyWireResponse(response.bodyAsText())
  }

  override suspend fun remove() {
    val response = httpClient.delete(byId)
    checkSuccess(response)
    onRemoved(taskId)
  }

  override suspend fun setSpeedLimit(limit: SpeedLimit) {
    val response = httpClient.put(
      Api.Tasks.ById.SpeedLimit(parent = byId)
    ) {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(
        SpeedLimitRequest.serializer(),
        SpeedLimitRequest(limit.bytesPerSecond)
      ))
    }
    checkSuccess(response)
    applyWireResponse(response.bodyAsText())
  }

  override suspend fun setPriority(priority: DownloadPriority) {
    val response = httpClient.put(
      Api.Tasks.ById.Priority(parent = byId)
    ) {
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(
        PriorityRequest.serializer(),
        PriorityRequest(priority.name)
      ))
    }
    checkSuccess(response)
    applyWireResponse(response.bodyAsText())
  }

  override suspend fun reschedule(
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition>
  ) {
    throw UnsupportedOperationException(
      "Rescheduling is not supported for remote tasks"
    )
  }

  override suspend fun await(): Result<String> {
    val finalState = state.first { it.isTerminal }
    return when (finalState) {
      is DownloadState.Completed ->
        Result.success(finalState.filePath)
      is DownloadState.Failed ->
        Result.failure(finalState.error)
      is DownloadState.Canceled ->
        Result.failure(KDownError.Canceled)
      else -> Result.failure(KDownError.Unknown(null))
    }
  }

  private fun applyWireResponse(body: String) {
    val wire: TaskResponse = json.decodeFromString(body)
    _state.value = WireMapper.toDownloadState(wire)
    _segments.value = WireMapper.toSegments(wire.segments)
  }

  private fun checkSuccess(
    response: io.ktor.client.statement.HttpResponse
  ) {
    if (!response.status.isSuccess()) {
      throw IllegalStateException(
        "HTTP ${response.status.value}: " +
          response.status.description
      )
    }
  }
}
