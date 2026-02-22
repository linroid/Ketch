package com.linroid.ketch.remote

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.ConnectionsRequest
import com.linroid.ketch.endpoints.model.PriorityRequest
import com.linroid.ketch.endpoints.model.SpeedLimitRequest
import com.linroid.ketch.endpoints.model.TaskSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.delete
import io.ktor.client.plugins.resources.post
import io.ktor.client.plugins.resources.put
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.time.Instant

internal class RemoteDownloadTask(
  override val taskId: String,
  override val request: DownloadRequest,
  override val createdAt: Instant,
  initialState: DownloadState,
  initialSegments: List<Segment>,
  private val httpClient: HttpClient,
  private val onRemoved: suspend (String) -> Unit,
) : DownloadTask {
  private val log = KetchLogger("RemoteTask")

  private val _state = MutableStateFlow(initialState)
  override val state: StateFlow<DownloadState> = _state.asStateFlow()

  private val _segments = MutableStateFlow(initialSegments)
  override val segments: StateFlow<List<Segment>> =
    _segments.asStateFlow()

  internal fun updateState(newState: DownloadState) {
    log.d { "State update for taskId=$taskId: $newState" }
    _state.value = newState
  }

  private val byId get() = Api.Tasks.ById(id = taskId)

  override suspend fun pause() {
    log.d { "Pause taskId=$taskId" }
    val response = httpClient.post(
      Api.Tasks.ById.Pause(parent = byId),
    )
    checkSuccess(response)
    update(response.body())
  }

  override suspend fun resume(destination: Destination?) {
    log.d { "Resume taskId=$taskId" }
    val response = httpClient.post(
      Api.Tasks.ById.Resume(
        parent = byId,
        destination = destination?.value,
      ),
    )
    checkSuccess(response)
    update(response.body())
  }

  override suspend fun cancel() {
    log.d { "Cancel taskId=$taskId" }
    val response = httpClient.post(
      Api.Tasks.ById.Cancel(parent = byId),
    )
    checkSuccess(response)
    update(response.body())
  }

  override suspend fun remove() {
    log.d { "Remove taskId=$taskId" }
    val response = httpClient.delete(byId)
    checkSuccess(response)
    onRemoved(taskId)
  }

  override suspend fun setSpeedLimit(limit: SpeedLimit) {
    val response = httpClient.put(
      Api.Tasks.ById.SpeedLimit(parent = byId),
    ) {
      contentType(ContentType.Application.Json)
      setBody(SpeedLimitRequest(limit))
    }
    checkSuccess(response)
    update(response.body())
  }

  override suspend fun setPriority(priority: DownloadPriority) {
    val response = httpClient.put(
      Api.Tasks.ById.Priority(parent = byId),
    ) {
      contentType(ContentType.Application.Json)
      setBody(PriorityRequest(priority))
    }
    checkSuccess(response)
    update(response.body())
  }

  override suspend fun setConnections(connections: Int) {
    val response = httpClient.put(
      Api.Tasks.ById.Connections(parent = byId),
    ) {
      contentType(ContentType.Application.Json)
      setBody(ConnectionsRequest(connections))
    }
    checkSuccess(response)
    update(response.body())
  }

  override suspend fun reschedule(
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition>,
  ) {
    throw UnsupportedOperationException(
      "Rescheduling is not supported for remote tasks",
    )
  }

  private fun update(response: TaskSnapshot) {
    _state.value = response.state
    _segments.value = response.segments
  }

  private fun checkSuccess(
    response: io.ktor.client.statement.HttpResponse,
  ) {
    if (!response.status.isSuccess()) {
      log.e {
        "HTTP error ${response.status.value} for taskId=$taskId"
      }
      throw IllegalStateException(
        "HTTP ${response.status.value}: " +
          response.status.description,
      )
    }
  }
}
