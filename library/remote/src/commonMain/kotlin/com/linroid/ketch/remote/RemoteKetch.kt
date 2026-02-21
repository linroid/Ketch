package com.linroid.ketch.remote

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.config.DownloadConfig
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.ResolveUrlRequest
import com.linroid.ketch.endpoints.model.TaskEvent
import com.linroid.ketch.endpoints.model.TaskSnapshot
import com.linroid.ketch.endpoints.model.TasksResponse
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.HttpTimeout
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
import io.ktor.client.statement.HttpResponse
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

/**
 * Remote implementation of [KetchApi] that communicates with a
 * Ketch daemon server over HTTP + SSE.
 *
 * @param host server hostname or IP address
 * @param port server port (default 8642)
 * @param apiToken optional Bearer token for authentication
 * @param secure use HTTPS instead of HTTP
 */
class RemoteKetch(
  val host: String,
  val port: Int = 8642,
  private val apiToken: String? = null,
  val secure: Boolean = false,
) : KetchApi {

  private val scheme = if (secure) "https" else "http"
  private val baseUrl = "$scheme://$host:$port"
  private val scope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default,
  )
  private val log = KetchLogger("RemoteKetch")

  private val json = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    isLenient = true
    coerceInputValues = true
  }

  internal val httpClient = HttpClient {
    install(HttpTimeout) {
      socketTimeoutMillis = Long.MAX_VALUE
      requestTimeoutMillis = Long.MAX_VALUE
    }
    install(ContentNegotiation) {
      json(this@RemoteKetch.json)
    }
    install(SSE)
    install(Resources)
    defaultRequest {
      url(baseUrl)
      if (apiToken != null) {
        header(
          HttpHeaders.Authorization, "Bearer $apiToken",
        )
      }
    }
  }

  private val _connectionState = MutableStateFlow<ConnectionState>(
    ConnectionState.Disconnected("Not started"),
  )

  val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

  override val backendLabel: String =
    "Remote · $host:$port"

  private val taskMap = mutableMapOf<String, RemoteDownloadTask>()

  private val _tasks = MutableStateFlow<List<DownloadTask>>(
    emptyList(),
  )
  override val tasks: StateFlow<List<DownloadTask>> =
    _tasks.asStateFlow()

  private var sseJob: Job? = null

  override suspend fun download(
    request: DownloadRequest,
  ): DownloadTask {
    log.i { "Download: url=${request.url}" }
    val response = httpClient.post(Api.Tasks()) {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    checkSuccess(response)
    val task = createRemoteTask(response.body())
    addOrUpdate(task)
    return task
  }

  override suspend fun resolve(
    url: String,
    headers: Map<String, String>,
  ): ResolvedSource {
    val response = httpClient.post(Api.Resolve()) {
      contentType(ContentType.Application.Json)
      setBody(ResolveUrlRequest(url, headers))
    }
    checkSuccess(response)
    return response.body()
  }

  override suspend fun start() {
    log.i { "Connecting to $host:$port" }
    if (sseJob?.isActive == true) return
    _connectionState.value = ConnectionState.Connecting
    sseJob = scope.launch { connectSse() }
  }

  override suspend fun status(): KetchStatus {
    val response = httpClient.get(Api.Status())
    checkSuccess(response)
    return response.body()
  }

  override suspend fun updateConfig(config: DownloadConfig) {
    log.d { "Updating config: speedLimit=${config.speedLimit}" }
    val response = httpClient.put(Api.Config()) {
      contentType(ContentType.Application.Json)
      setBody(config)
    }
    checkSuccess(response)
  }

  override fun close() {
    log.d { "Close" }
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
          urlString = "/api/events",
        ) {
          log.i { "Connected to /events" }
          incoming.collect { event ->
            val data = event.data ?: return@collect
            try {
              val event: TaskEvent = json.decodeFromString(data)
              handleEvent(event)
            } catch (error: Exception) {
              log.e(error) { "Failed to handle event" }
            }
          }
        }
      } catch (_: UnauthorizedException) {
        _connectionState.value = ConnectionState.Unauthorized
        return
      } catch (error: Exception) {
        log.e(error) { "Failed to connect" }
      }

      _connectionState.value = ConnectionState.Disconnected()

      log.d { "Reconnecting in ${reconnectDelayMs}ms" }
      delay(reconnectDelayMs)
      _connectionState.value = ConnectionState.Connecting
      reconnectDelayMs = (reconnectDelayMs * 2)
        .coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }
  }

  private suspend fun fetchAllTasks() {
    log.i { "Fetch all tasks" }
    val response = httpClient.get(Api.Tasks())
    log.i { "Status: ${response.status}" }
    if (response.status.value == 401) {
      throw UnauthorizedException()
    }
    if (response.status.isSuccess()) {
      val snapshots: TasksResponse = response.body()
      log.i { "Fetched ${snapshots.tasks.size} tasks" }
      taskMap.clear()
      val tasks = snapshots.tasks.map(::createRemoteTask)
      tasks.forEach { taskMap[it.taskId] = it }
      _tasks.value = tasks
      log.i { "Fetched ${snapshots.tasks.size} tasks -> ${tasks.size}" }
    }
  }

  private suspend fun handleEvent(event: TaskEvent) {
    log.i { "Handle event: $event" }
    when (event) {
      is TaskEvent.TaskAdded -> {
        try {
          val response = httpClient.get(
            Api.Tasks.ById(id = event.taskId),
          )
          if (response.status.isSuccess()) {
            val wire: TaskSnapshot = response.body()
            val task = createRemoteTask(wire)
            addOrUpdate(task)
          }
        } catch (e: Exception) {
          log.w(e) { "Failed to fetch added task: ${event.taskId}" }
        }
      }

      is TaskEvent.TaskRemoved -> {
        removeTask(event.taskId)
      }

      is TaskEvent.StateChanged -> {
        val task = taskMap[event.taskId] ?: return
        task.updateState(event.state)
      }

      is TaskEvent.Progress -> {
        val task = taskMap[event.taskId] ?: return
        task.updateState(event.state)
      }

      is TaskEvent.Error -> {
        log.w { "Server error for task ${event.taskId}" }
      }
    }
  }

  private fun createRemoteTask(wire: TaskSnapshot): RemoteDownloadTask {
    return RemoteDownloadTask(
      taskId = wire.taskId,
      request = wire.request,
      createdAt = wire.createdAt,
      initialState = wire.state,
      initialSegments = wire.segments,
      httpClient = httpClient,
      onRemoved = { id ->
        taskMap.remove(id)
        _tasks.value = _tasks.value.filter {
          it.taskId != id
        }
      },
    )
  }

  private fun addOrUpdate(task: RemoteDownloadTask) {
    log.i { "Add or update: ${task.taskId}" }
    taskMap[task.taskId] = task
    _tasks.update { taskMap.values.toList() }
  }

  private fun removeTask(taskId: String) {
    log.i { "Remove task $taskId" }
    taskMap.remove(taskId)
    _tasks.update { taskMap.values.toList() }
  }

  private fun checkSuccess(response: HttpResponse) {
    if (!response.status.isSuccess()) {
      log.w { "HTTP error ${response.status.value}" }
      throw IllegalStateException(
        "HTTP ${response.status.value}: " +
          response.status.description,
      )
    }
  }

  private class UnauthorizedException :
    Exception("Server requires authentication")

  companion object {
    private const val INITIAL_RECONNECT_DELAY_MS = 1000L
    private const val MAX_RECONNECT_DELAY_MS = 30_000L
  }
}
