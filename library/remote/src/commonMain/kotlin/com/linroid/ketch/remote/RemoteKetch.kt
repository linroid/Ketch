package com.linroid.ketch.remote

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.api.ProxyConfig
import com.linroid.ketch.api.ProxyMode
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.ResolveUrlRequest
import com.linroid.ketch.endpoints.model.TaskEvent
import com.linroid.ketch.endpoints.model.TaskSnapshot
import com.linroid.ketch.endpoints.model.TasksResponse
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
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
import io.ktor.http.HttpStatusCode
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * Remote implementation of [KetchApi] that communicates with a
 * Ketch daemon server over HTTP + SSE.
 *
 * Calls the server refuses, here and on its tasks, throw [RemoteApiException] with the server's
 * reason, unless [KetchApi] names another exception for the case.
 *
 * @param host server hostname or IP address
 * @param port server port (default 8642)
 * @param apiToken optional Bearer token for authentication
 * @param secure use HTTPS instead of HTTP
 */
class RemoteKetch internal constructor(
  val host: String,
  val port: Int,
  private val apiToken: String?,
  val secure: Boolean,
  engine: HttpClientEngine?,
) : KetchApi {
  constructor(
    host: String,
    port: Int = 8642,
    apiToken: String? = null,
    secure: Boolean = false,
  ) : this(host, port, apiToken, secure, null)


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

  private val configureClient: HttpClientConfig<*>.() -> Unit = {
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

  internal val httpClient = if (engine == null) HttpClient(configureClient)
    else HttpClient(engine, configureClient)

  private val _connectionState = MutableStateFlow<ConnectionState>(
    ConnectionState.Disconnected("Not started"),
  )

  val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

  override val backendLabel: String =
    "Remote · $host:$port"

  private val taskMutex = Mutex()
  private val taskMap = mutableMapOf<String, RemoteDownloadTask>()
  private var taskRevision = 0L

  private val _tasks = MutableStateFlow<List<DownloadTask>>(
    emptyList(),
  )
  override val tasks: StateFlow<List<DownloadTask>> =
    _tasks.asStateFlow()

  private val sseMutex = Mutex()
  private var sseJob: Job? = null

  override suspend fun download(
    request: DownloadRequest,
  ): DownloadTask {
    log.i { "Download: url=${redactUrl(request.url)}" }
    requireProxySupport(request.proxy)
    val response = httpClient.post(Api.Tasks()) {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    checkSuccess(response)
    val task = createRemoteTask(response.body())
    return taskMutex.withLock {
      // SSE may already have delivered newer progress or completion before this POST returns.
      taskMap[task.taskId] ?: addOrUpdate(task)
    }
  }

  override suspend fun resolve(
    url: String,
    properties: Map<String, String>,
  ): ResolvedSource {
    val response = httpClient.post(Api.Resolve()) {
      contentType(ContentType.Application.Json)
      setBody(ResolveUrlRequest(url, properties))
    }
    checkSuccess(response)
    return response.body()
  }

  override suspend fun resolveContent(
    content: ByteArray,
    fileName: String?,
  ): ResolvedSource {
    val response = httpClient.post(Api.Resolve.Content(fileName = fileName)) {
      contentType(ContentType.Application.OctetStream)
      setBody(content)
    }
    when (response.status) {
      HttpStatusCode.UnsupportedMediaType -> throw KetchError.Unsupported()
      HttpStatusCode.UnprocessableEntity -> throw response.body<KetchError>()
      HttpStatusCode.PayloadTooLarge -> throw IllegalArgumentException("File is too large")
      HttpStatusCode.NotFound, HttpStatusCode.NotImplemented ->
        throw UnsupportedOperationException("Resolving file content is unavailable")
    }
    checkSuccess(response)
    return response.body()
  }

  override suspend fun start() {
    log.i { "Connecting to $host:$port" }
    sseMutex.withLock {
      if (sseJob?.isActive == true) return
      _connectionState.value = ConnectionState.Connecting
      sseJob = scope.launch { connectSse() }
    }
  }

  override suspend fun status(): KetchStatus {
    val response = httpClient.get(Api.Status())
    checkSuccess(response)
    return response.body()
  }

  override suspend fun updateConfig(config: DownloadConfig) {
    log.d { "Updating config: speedLimit=${config.speedLimit}" }
    requireProxySupport(config.proxy)
    val response = httpClient.put(Api.Config()) {
      contentType(ContentType.Application.Json)
      setBody(config)
    }
    if (response.status == HttpStatusCode.BadRequest) {
      // The server explains why, e.g. a download folder that does not exist.
      val message = runCatching { response.body<ErrorResponse>().message }.getOrNull()
      throw IllegalArgumentException(message ?: "Invalid download settings")
    }
    checkSuccess(response)
  }

  /**
   * Refuses [proxy] unless the server lists [KetchFeatures.PROXY]: an older server would ignore
   * it and connect without the proxy.
   */
  private suspend fun requireProxySupport(proxy: ProxyConfig?) {
    if (proxy == null || proxy.mode == ProxyMode.SYSTEM) return
    if (KetchFeatures.PROXY !in status().features) {
      throw UnsupportedOperationException("This device cannot use a proxy")
    }
  }

  override suspend fun networkInterfaces(): NetworkInterfaces {
    val response = httpClient.get(Api.NetworkInterfaces())
    if (response.status == HttpStatusCode.NotFound ||
      response.status == HttpStatusCode.NotImplemented
    ) return NetworkInterfaces()
    checkSuccess(response)
    return response.body()
  }

  override suspend fun updateNetworkInterfaces(config: NetworkInterfaceConfig): NetworkInterfaces {
    val response = httpClient.put(Api.NetworkInterfaces()) {
      contentType(ContentType.Application.Json)
      setBody(config)
    }
    when (response.status) {
      HttpStatusCode.BadRequest -> throw IllegalArgumentException("Invalid network interface selection")
      HttpStatusCode.NotFound, HttpStatusCode.NotImplemented ->
        throw UnsupportedOperationException("HTTP network interface configuration is unavailable")
    }
    checkSuccess(response)
    return response.body()
  }

  override fun close() {
    log.d { "Close" }
    try {
      scope.cancel()
    } finally {
      httpClient.close()
    }
  }

  // -- SSE connection with reconnection --

  private suspend fun connectSse() {
    var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    var failures = 0
    while (true) {
      try {
        fetchAllTasks()
        _connectionState.value = ConnectionState.Connected
        reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
        failures = 0
        httpClient.sse(
          urlString = "/api/events",
        ) {
          log.i { "Connected to $baseUrl/api/events" }
          incoming.collect { sseEvent ->
            val data = sseEvent.data ?: return@collect
            try {
              val taskEvent: TaskEvent =
                json.decodeFromString(data)
              handleEvent(taskEvent)
            } catch (error: Exception) {
              log.e(error) { "Failed to handle event" }
            }
          }
        }
      } catch (_: UnauthorizedException) {
        log.w { "Server $baseUrl rejected the API token (HTTP 401); not reconnecting" }
        _connectionState.value = ConnectionState.Unauthorized
        return
      } catch (error: Exception) {
        // The stack trace is printed once per outage, not on every reconnect attempt.
        failures++
        log.w(error.takeIf { failures == 1 }) {
          "Connection to $baseUrl failed (attempt $failures): ${error.describeCauses()}"
        }
      }

      _connectionState.value = ConnectionState.Disconnected()

      log.d { "Reconnecting to $baseUrl in ${reconnectDelayMs}ms" }
      delay(reconnectDelayMs)
      _connectionState.value = ConnectionState.Connecting
      reconnectDelayMs = (reconnectDelayMs * 2)
        .coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }
  }

  internal suspend fun fetchAllTasks() {
    while (true) {
      val revision = taskMutex.withLock { taskRevision }
      log.i { "Fetch all tasks" }
      val response = httpClient.get(Api.Tasks())
      if (response.status.value == 401) throw UnauthorizedException()
      checkSuccess(response)
      val snapshots: TasksResponse = response.body()
      val tasks = snapshots.tasks.map(::createRemoteTask)
      val applied = taskMutex.withLock {
        // A concurrent POST, removal or SSE event makes this snapshot stale. Fetch again
        // without holding the map lock so those operations can continue immediately.
        if (taskRevision != revision) return@withLock false
        val ids = tasks.map { it.taskId }.toSet()
        taskMap.keys.retainAll(ids)
        tasks.forEach { addOrUpdate(it) }
        taskRevision++
        _tasks.value = taskMap.values.toList()
        true
      }
      if (applied) return
    }
  }

  internal suspend fun handleEvent(event: TaskEvent) {
    // Events carry request headers and segments; progress arrives several times a second.
    if (event is TaskEvent.Progress) {
      log.v { "Progress event for taskId=${event.taskId}" }
    } else {
      log.d { "Event ${event.eventType} for taskId=${event.taskId}" }
    }
    when (event) {
      is TaskEvent.TaskAdded -> {
        // Some servers send no state after this event until the task changes, so the snapshot
        // is fetched even for a task this client already knows.
        val known = taskMutex.withLock { taskMap[event.taskId]?.let { it to it.updates } }
        try {
          val response = httpClient.get(
            Api.Tasks.ById(id = event.taskId),
          )
          if (response.status.isSuccess()) {
            val wire: TaskSnapshot = response.body()
            val task = createRemoteTask(wire)
            taskMutex.withLock {
              // A POST response, command or SSE event may have reported newer state while the
              // snapshot was in flight, or the task may have been removed; never write the
              // older snapshot over either.
              val current = taskMap[task.taskId]
              val unchanged = if (known == null) {
                current == null
              } else {
                current === known.first && current.updates == known.second
              }
              if (unchanged) addOrUpdate(task)
            }
          }
        } catch (e: Exception) {
          log.w(e) { "Failed to fetch added task: ${event.taskId}" }
        }
      }

      is TaskEvent.TaskRemoved -> {
        taskMutex.withLock { removeTask(event.taskId) }
      }

      is TaskEvent.StateChanged -> {
        taskMutex.withLock {
          val task = taskMap[event.taskId] ?: return
          task.applyEvent(event.state, event.request, event.segments, event.queuePosition)
          taskRevision++
        }
      }

      is TaskEvent.Progress -> {
        taskMutex.withLock {
          val task = taskMap[event.taskId] ?: return
          // A downloading task no longer waits in the queue.
          task.applyEvent(event.state, event.request, event.segments, queuePosition = null)
          taskRevision++
        }
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
      initialQueuePosition = wire.queuePosition,
      httpClient = httpClient,
      onRemoved = { id ->
        taskMutex.withLock { removeTask(id) }
      },
      updateLock = taskMutex,
    )
  }

  private fun addOrUpdate(task: RemoteDownloadTask): RemoteDownloadTask {
    log.i { "Add or update: ${task.taskId}" }
    taskRevision++
    // POST responses, TaskAdded events and reconnect snapshots can describe the same task.
    // Preserve the instance already returned to callers so its StateFlows keep receiving SSE.
    val current = taskMap[task.taskId]
    if (current != null) {
      current.updateState(
        task.state.value,
        task.request,
        task.segments.value,
        task.queuePosition.value,
      )
    } else {
      taskMap[task.taskId] = task
    }
    _tasks.update { taskMap.values.toList() }
    return current ?: task
  }

  private fun removeTask(taskId: String) {
    log.i { "Remove task $taskId" }
    taskRevision++
    taskMap.remove(taskId)
    _tasks.update { taskMap.values.toList() }
  }

  private suspend fun checkSuccess(response: HttpResponse) {
    if (response.status.isSuccess()) return
    val error = response.toRemoteApiException()
    log.w { "HTTP error ${error.status}: ${error.errorCode ?: "no error code"}" }
    throw error
  }

  private class UnauthorizedException :
    Exception("Server requires authentication")

  companion object {
    private const val INITIAL_RECONNECT_DELAY_MS = 1000L
    private const val MAX_RECONNECT_DELAY_MS = 30_000L
  }
}
