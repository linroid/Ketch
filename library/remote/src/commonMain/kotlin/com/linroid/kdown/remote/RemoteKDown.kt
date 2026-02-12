package com.linroid.kdown.remote

import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.DownloadTask
import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.api.KDownVersion
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.endpoints.Api
import com.linroid.kdown.endpoints.model.CreateDownloadRequest
import com.linroid.kdown.endpoints.model.SpeedLimitRequest
import com.linroid.kdown.endpoints.model.TaskEvent
import com.linroid.kdown.endpoints.model.TaskResponse
import io.ktor.client.HttpClient
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.resources.Resources
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.plugins.resources.put
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Remote implementation of [KDownApi] that communicates with a
 * KDown daemon server over HTTP + SSE.
 *
 * @param baseUrl server base URL (e.g., "http://localhost:8642")
 * @param apiToken optional Bearer token for authentication
 */
class RemoteKDown(
  private val baseUrl: String,
  private val apiToken: String? = null
) : KDownApi {

  private val scope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default
  )

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
  }

  internal val httpClient = HttpClient {
    install(ContentNegotiation) {
      json(this@RemoteKDown.json)
    }
    install(SSE)
    install(Resources)
    defaultRequest {
      url(baseUrl)
      if (apiToken != null) {
        header(
          HttpHeaders.Authorization, "Bearer $apiToken"
        )
      }
    }
  }

  private val _connectionState = MutableStateFlow<ConnectionState>(
    ConnectionState.Connecting
  )

  private val _version = MutableStateFlow(KDownVersion(KDownVersion.DEFAULT, "unknown"))

  override val version: StateFlow<KDownVersion> = _version.asStateFlow()

  val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

  override val backendLabel: String =
    "Remote · ${
      baseUrl.removePrefix("http://")
        .removePrefix("https://")
    }"

  private val taskMap = mutableMapOf<String, RemoteDownloadTask>()

  private val _tasks = MutableStateFlow<List<DownloadTask>>(
    emptyList()
  )
  override val tasks: StateFlow<List<DownloadTask>> =
    _tasks.asStateFlow()

  private var sseJob: Job? = null

  init {
    sseJob = scope.launch { connectSse() }
  }

  override suspend fun download(
    request: DownloadRequest
  ): DownloadTask {
    val wireRequest = WireMapper.toCreateWire(request)
    val response = httpClient.post(Api.Tasks()) {
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          CreateDownloadRequest.serializer(), wireRequest
        )
      )
    }
    checkSuccess(response)
    val wire: TaskResponse = json.decodeFromString(
      response.bodyAsText()
    )
    val task = createRemoteTask(wire, request)
    addTask(task)
    return task
  }

  override suspend fun setGlobalSpeedLimit(limit: SpeedLimit) {
    val response = httpClient.put(Api.SpeedLimit()) {
      contentType(ContentType.Application.Json)
      setBody(
        json.encodeToString(
          SpeedLimitRequest.serializer(),
          SpeedLimitRequest(limit.bytesPerSecond)
        )
      )
    }
    checkSuccess(response)
  }

  override fun close() {
    scope.cancel()
    httpClient.close()
  }

  // -- SSE connection with reconnection --

  private suspend fun connectSse() {
    var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    while (true) {
      try {
        fetchAllTasks()
        _connectionState.value = ConnectionState.Connected
        reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS

        httpClient.sse(
          urlString = "/api/events"
        ) {
          incoming.collect { event ->
            val data = event.data ?: return@collect
            try {
              val wireEvent: TaskEvent =
                json.decodeFromString(data)
              handleEvent(wireEvent)
            } catch (_: Exception) {
              // Skip malformed events
            }
          }
        }
      } catch (_: Exception) {
        // Connection lost or failed
      }

      _connectionState.value = ConnectionState.Disconnected()

      delay(reconnectDelayMs)
      _connectionState.value = ConnectionState.Connecting
      reconnectDelayMs = (reconnectDelayMs * 2)
        .coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }
  }

  private suspend fun fetchAllTasks() {
    val response = httpClient.get(Api.Tasks())
    if (response.status.isSuccess()) {
      val wireTasks: List<TaskResponse> =
        json.decodeFromString(response.bodyAsText())
      taskMap.clear()
      val tasks = wireTasks.map { wire ->
        val request = WireMapper.toDownloadRequest(wire)
        createRemoteTask(wire, request)
      }
      tasks.forEach { taskMap[it.taskId] = it }
      _tasks.value = tasks
    }
  }

  private suspend fun handleEvent(event: TaskEvent) {
    when (event.type) {
      "task_added" -> {
        try {
          val response = httpClient.get(
            Api.Tasks.ById(id = event.taskId)
          )
          if (response.status.isSuccess()) {
            val wire: TaskResponse =
              json.decodeFromString(response.bodyAsText())
            val request = WireMapper.toDownloadRequest(wire)
            val task = createRemoteTask(wire, request)
            addTask(task)
          }
        } catch (_: Exception) {
          // Task may already be gone
        }
      }

      "task_removed" -> {
        taskMap.remove(event.taskId)
        _tasks.value = _tasks.value.filter {
          it.taskId != event.taskId
        }
      }

      "state_changed", "progress" -> {
        val task = taskMap[event.taskId] ?: return
        val newState = WireMapper.toDownloadState(
          event.state,
          event.progress,
          event.error,
          event.filePath
        )
        task.updateState(newState)
      }
    }
  }

  private fun createRemoteTask(
    wire: TaskResponse,
    request: DownloadRequest
  ): RemoteDownloadTask {
    return RemoteDownloadTask(
      taskId = wire.taskId,
      request = request,
      createdAt = WireMapper.parseCreatedAt(wire.createdAt),
      initialState = WireMapper.toDownloadState(wire),
      initialSegments = WireMapper.toSegments(wire.segments),
      httpClient = httpClient,
      json = json,
      onRemoved = { id ->
        taskMap.remove(id)
        _tasks.value = _tasks.value.filter {
          it.taskId != id
        }
      }
    )
  }

  private fun addTask(task: RemoteDownloadTask) {
    taskMap[task.taskId] = task
    _tasks.value += task
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

  companion object {
    private const val INITIAL_RECONNECT_DELAY_MS = 1000L
    private const val MAX_RECONNECT_DELAY_MS = 30_000L
  }
}
