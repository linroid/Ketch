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
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.PriorityRequest
import com.linroid.ketch.endpoints.model.SpeedLimitRequest
import com.linroid.ketch.endpoints.model.TaskSnapshot
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.delete
import io.ktor.client.plugins.resources.post
import io.ktor.client.plugins.resources.put
import io.ktor.client.request.parameter
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Instant

internal class RemoteDownloadTask(
  override val taskId: String,
  request: DownloadRequest,
  override val createdAt: Instant,
  initialState: DownloadState,
  initialSegments: List<Segment>,
  initialQueuePosition: Int?,
  private val httpClient: HttpClient,
  private val onRemoved: suspend (String) -> Unit,
  /** Held while server updates are applied; [RemoteKetch] passes the lock it applies SSE under. */
  private val updateLock: Mutex = Mutex(),
) : DownloadTask {
  private val mutableRequest = MutableStateFlow(request)
  override val requestState: StateFlow<DownloadRequest> = mutableRequest.asStateFlow()
  override val request: DownloadRequest get() = requestState.value

  private val log = KetchLogger("RemoteTask")

  private val _state = MutableStateFlow(initialState)
  override val state: StateFlow<DownloadState> = _state.asStateFlow()

  private val _segments = MutableStateFlow(initialSegments)
  override val segments: StateFlow<List<Segment>> =
    _segments.asStateFlow()

  private val _queuePosition = MutableStateFlow(initialQueuePosition)
  override val queuePosition: StateFlow<Int?> = _queuePosition.asStateFlow()

  /**
   * How many SSE events [applyEvent] has applied, guarded by [updateLock]. Commands read it
   * before sending their request, so their response can tell whether the stream reported
   * something newer while the request was in flight.
   */
  private var appliedEvents = 0L

  /** Applies an SSE event; the caller holds [updateLock]. */
  internal fun applyEvent(
    newState: DownloadState,
    request: DownloadRequest?,
    segments: List<Segment>?,
    queuePosition: Int?,
  ) {
    appliedEvents++
    updateState(newState, request, segments, queuePosition)
  }

  /**
   * Applies what the server reported. Null [request] or [segments] come from an older server
   * that omitted them and keep the current values; a null [queuePosition] means the task does
   * not wait (or the server does not report positions), so it is always applied.
   */
  internal fun updateState(
    newState: DownloadState,
    request: DownloadRequest?,
    segments: List<Segment>?,
    queuePosition: Int?,
  ) {
    request?.let { mutableRequest.value = it }
    segments?.let { _segments.value = it }
    _queuePosition.value = queuePosition
    val previous = _state.value
    if (previous::class != newState::class) {
      log.d {
        "State update for taskId=$taskId: " +
          "${previous::class.simpleName} -> ${newState::class.simpleName}"
      }
    }
    _state.value = newState
  }

  private val byId get() = Api.Tasks.ById(id = taskId)

  override suspend fun pause() {
    log.d { "Pause taskId=$taskId" }
    val events = appliedEvents()
    val response = httpClient.post(
      Api.Tasks.ById.Pause(parent = byId),
    )
    checkSuccess(response)
    update(response.body(), events)
  }

  override suspend fun resume(destination: Destination?) {
    log.d { "Resume taskId=$taskId" }
    val events = appliedEvents()
    val response = httpClient.post(
      Api.Tasks.ById.Resume(
        parent = byId,
        destination = destination?.value,
      ),
    )
    checkSuccess(response)
    update(response.body(), events)
  }

  override suspend fun cancel() {
    log.d { "Cancel taskId=$taskId" }
    val events = appliedEvents()
    val response = httpClient.post(
      Api.Tasks.ById.Cancel(parent = byId),
    )
    checkSuccess(response)
    update(response.body(), events)
  }

  override suspend fun remove(deleteFiles: Boolean) {
    log.d { "Remove taskId=$taskId, deleteFiles=$deleteFiles" }
    val response = httpClient.delete(byId) {
      if (deleteFiles) parameter("deleteFiles", "true")
    }
    checkSuccess(response)
    onRemoved(taskId)
  }

  override suspend fun setSpeedLimit(limit: SpeedLimit) {
    val events = appliedEvents()
    val response = httpClient.put(
      Api.Tasks.ById.SpeedLimit(parent = byId),
    ) {
      contentType(ContentType.Application.Json)
      setBody(SpeedLimitRequest(limit))
    }
    checkSuccess(response)
    update(response.body(), events)
  }

  override suspend fun setPriority(priority: DownloadPriority) {
    val events = appliedEvents()
    val response = httpClient.put(
      Api.Tasks.ById.Priority(parent = byId),
    ) {
      contentType(ContentType.Application.Json)
      setBody(PriorityRequest(priority))
    }
    checkSuccess(response)
    update(response.body(), events)
  }

  override suspend fun setConnections(connections: Int) {
    require(connections >= 0) { "Connections must not be negative" }
    val events = appliedEvents()
    val response = httpClient.put(
      Api.Tasks.ById.Connections(parent = byId),
    ) {
      contentType(ContentType.Application.Json)
      setBody(ConnectionsRequest(connections))
    }
    // Servers that predate Auto reject 0 like any other invalid count.
    if (connections == 0 && response.status == HttpStatusCode.BadRequest) {
      val code = runCatching { response.body<ErrorResponse>().error }.getOrNull()
      if (code == "invalid_connections") {
        throw UnsupportedOperationException("This server does not support Auto connections")
      }
    }
    checkSuccess(response)
    update(response.body(), events)
  }

  override suspend fun reschedule(
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition>,
  ) {
    throw UnsupportedOperationException(
      "Rescheduling is not supported for remote tasks",
    )
  }

  private suspend fun appliedEvents(): Long = updateLock.withLock { appliedEvents }

  private suspend fun update(response: TaskSnapshot, eventsBefore: Long) {
    updateLock.withLock {
      // Other tasks move this one in the queue, and the server sends every move over SSE. An
      // event applied while the request was in flight may be newer than this response, and
      // nothing would correct an older position written over it, so the event's position wins.
      val position = if (appliedEvents == eventsBefore) {
        response.queuePosition
      } else {
        _queuePosition.value
      }
      updateState(response.state, response.request, response.segments, position)
    }
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
