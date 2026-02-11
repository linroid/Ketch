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
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path
import kotlinx.serialization.json.Json
import kotlin.time.Instant

internal class RemoteDownloadTask(
  override val taskId: String,
  override val request: DownloadRequest,
  override val createdAt: Instant,
  initialState: DownloadState,
  initialSegments: List<Segment>,
  private val httpClient: HttpClient,
  private val baseUrl: String,
  private val apiToken: String?,
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

  override suspend fun pause() {
    val response = httpClient.post(
      "$baseUrl/api/downloads/$taskId/pause"
    ) { applyAuth() }
    checkSuccess(response)
    applyWireResponse(response.bodyAsText())
  }

  override suspend fun resume() {
    val response = httpClient.post(
      "$baseUrl/api/downloads/$taskId/resume"
    ) { applyAuth() }
    checkSuccess(response)
    applyWireResponse(response.bodyAsText())
  }

  override suspend fun cancel() {
    val response = httpClient.post(
      "$baseUrl/api/downloads/$taskId/cancel"
    ) { applyAuth() }
    checkSuccess(response)
    applyWireResponse(response.bodyAsText())
  }

  override suspend fun remove() {
    val response = httpClient.delete(
      "$baseUrl/api/downloads/$taskId"
    ) { applyAuth() }
    checkSuccess(response)
    onRemoved(taskId)
  }

  override suspend fun setSpeedLimit(limit: SpeedLimit) {
    val response = httpClient.put(
      "$baseUrl/api/downloads/$taskId/speed-limit"
    ) {
      applyAuth()
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(
        WireSpeedLimitBody.serializer(),
        WireSpeedLimitBody(limit.bytesPerSecond)
      ))
    }
    checkSuccess(response)
    applyWireResponse(response.bodyAsText())
  }

  override suspend fun setPriority(priority: DownloadPriority) {
    val response = httpClient.put(
      "$baseUrl/api/downloads/$taskId/priority"
    ) {
      applyAuth()
      contentType(ContentType.Application.Json)
      setBody(json.encodeToString(
        WirePriorityBody.serializer(),
        WirePriorityBody(priority.name)
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

  override suspend fun await(): Result<Path> {
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
    val wire: WireTaskResponse = json.decodeFromString(body)
    _state.value = WireMapper.toDownloadState(wire)
    _segments.value = WireMapper.toSegments(wire.segments)
  }

  private fun io.ktor.client.request.HttpRequestBuilder.applyAuth() {
    if (apiToken != null) {
      header(HttpHeaders.Authorization, "Bearer $apiToken")
    }
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
