package com.linroid.ketch.remote

import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.ControlKey
import com.linroid.ketch.api.CountryDatabase
import com.linroid.ketch.api.CountryDatabaseStatus
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.KetchStats
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.api.NoDownloadSourceException
import com.linroid.ketch.api.Proxy
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.ResolveUrlRequest
import com.linroid.ketch.endpoints.model.TaskEvent
import com.linroid.ketch.endpoints.model.TaskSnapshot
import com.linroid.ketch.endpoints.model.TasksResponse
import com.linroid.ketch.endpoints.stream.InterestError
import com.linroid.ketch.endpoints.stream.InterestRef
import com.linroid.ketch.endpoints.stream.InterestUpdate
import com.linroid.ketch.endpoints.stream.StreamError
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamHello
import com.linroid.ketch.endpoints.stream.StreamSerializers
import com.linroid.ketch.endpoints.stream.StreamSubscribed
import com.linroid.ketch.endpoints.stream.StreamTaskRef
import com.linroid.ketch.endpoints.stream.TaskProgressUpdate
import com.linroid.ketch.endpoints.stream.TaskSegmentsUpdate
import com.linroid.ketch.endpoints.stream.TaskStateUpdate
import com.linroid.ketch.torrent.api.TorrentController
import com.linroid.ketch.torrent.api.TorrentInterest
import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.call.body
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.plugins.defaultRequest
import io.ktor.client.plugins.resources.Resources
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.plugins.resources.prepareGet
import io.ktor.client.plugins.resources.put
import io.ktor.client.plugins.sse.SSE
import io.ktor.client.plugins.sse.sse
import io.ktor.client.request.header
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import io.ktor.serialization.kotlinx.json.json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.flow.transformWhile
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.updateAndGet
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Remote implementation of [KetchApi] that communicates with a
 * Ketch daemon server over HTTP + SSE.
 *
 * Calls the server refuses, here and on its tasks, throw [RemoteApiException] with the server's
 * reason, unless [KetchApi] names another exception for the case.
 *
 * Servers listing `events.stream` send their events on one stream that carries only what this
 * client asks for: every task's lifecycle and state changes at once, the progress of downloading
 * tasks every [progressInterval], and what its callers [subscribe] to, such as the segments of
 * the tasks they show. Set [foreground] to `false` while nothing is shown. Older servers send
 * every change of every task.
 *
 * @param host server hostname or IP address
 * @param port server port (default 8642)
 * @param apiToken optional Bearer token for authentication
 * @param secure use HTTPS instead of HTTP
 * @param progressInterval how often every downloading task's progress updates, or `null` for
 *   only with its state changes; see [progressInterval]
 */
class RemoteKetch internal constructor(
  val host: String,
  val port: Int,
  private val apiToken: String?,
  val secure: Boolean,
  engine: HttpClientEngine?,
  progressInterval: Duration? = DEFAULT_PROGRESS_INTERVAL,
) : KetchApi {
  constructor(
    host: String,
    port: Int = 8642,
    apiToken: String? = null,
    secure: Boolean = false,
    progressInterval: Duration? = DEFAULT_PROGRESS_INTERVAL,
  ) : this(host, port, apiToken, secure, null, progressInterval)


  private val scheme = if (secure) "https" else "http"
  private val baseUrl = "$scheme://$host:$port"
  private val scope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default,
  )
  private val log = KetchLogger("RemoteKetch")

  private val json = Json {
    ignoreUnknownKeys = true
    serializersModule = StreamSerializers
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

  /** Counts the event stream's connections; every reconnect makes earlier answers stale. */
  private val connectionGeneration = MutableStateFlow(0L)

  private val featuresMutex = Mutex()
  private var cachedFeatures: Pair<Long, Set<String>>? = null

  /**
   * Whether this client reads the server's event stream: once [start] connected to a server
   * that serves it. Topics beyond the tasks come from that stream then, and from their own
   * endpoints otherwise.
   */
  private val streamed = MutableStateFlow(false)
  private val interests = StreamInterests(progressInterval)
  private val topics = StreamTopics()

  /**
   * How often every downloading task's progress updates on a server with the event stream, or
   * `null` for only with its state changes. Changes apply at once.
   */
  var progressInterval: Duration?
    get() = interests.progress.value
    set(value) {
      require(value == null || value.isPositive()) { "progressInterval must be positive" }
      interests.progress.value = value
    }

  /**
   * Whether progress and the callers' interests come, `true` by default. An app in the
   * background sets it to `false`: the event stream then carries only the tasks' lifecycle and
   * state changes, and the rest comes again once it is `true`. Servers without the event stream
   * send everything.
   */
  var foreground: Boolean
    get() = interests.active.value
    set(value) {
      interests.active.value = value
    }

  /**
   * Tasks this client added since the event stream's last task snapshot, guarded by
   * [taskMutex]: the snapshot may predate them.
   */
  private val postedSinceSnapshot = mutableSetOf<String>()

  /**
   * Torrent controls of the server, over its `/api/torrents` routes. A server without them, an
   * older one or one without a torrent source, fails every command with
   * [com.linroid.ketch.torrent.api.TorrentCommandError.UNSUPPORTED] before sending it.
   */
  private val torrents = RemoteTorrentController(
    httpClient = httpClient,
    generation = connectionGeneration.asStateFlow(),
    json = json,
    streamed = streamed,
    streamTorrent = { interest, interval -> streamInterest(interest, interval) },
  )

  /**
   * The server's country database, over `/api/countries`; servers that do not list
   * [KetchFeatures.COUNTRIES] fail every call with [UnsupportedOperationException].
   */
  private val countries = object : CountryDatabase {
    override suspend fun status(): CountryDatabaseStatus {
      requireCountries()
      val response = httpClient.get(Api.Countries())
      checkSuccess(response)
      return response.body()
    }

    override suspend fun download(): CountryDatabaseStatus {
      requireCountries()
      val response = httpClient.post(Api.Countries.Download())
      checkSuccess(response)
      return response.body()
    }

    private suspend fun requireCountries() {
      if (KetchFeatures.COUNTRIES !in features()) {
        throw UnsupportedOperationException("This device has no country database")
      }
    }
  }

  /**
   * The server's controller for [key]: [TorrentController.Key] returns the server's torrent
   * controls and [CountryDatabase.Key] its country database, which fail closed against servers
   * without them. Other keys return `null`.
   */
  @Suppress("UNCHECKED_CAST")
  override fun <T : Any> controller(key: ControlKey<T>): T? = when {
    key === TorrentController.Key -> torrents as T
    key === CountryDatabase.Key -> countries as T
    else -> null
  }

  override suspend fun download(
    request: DownloadRequest,
  ): DownloadTask {
    log.i { "Download: url=${redactUrl(request.url)}" }
    requireProxySupport(request.proxy)
    // An older server would ignore the flag and download every file at once.
    if (request.awaitFileSelection &&
      KetchFeatures.AWAIT_FILE_SELECTION !in features()
    ) {
      throw UnsupportedOperationException("This device cannot wait for a choice of files")
    }
    // An older server would ignore the checksum and complete a file it never checked.
    if (request.checksum != null && KetchFeatures.CHECKSUM !in features()) {
      throw UnsupportedOperationException("This device cannot verify checksums")
    }
    val response = httpClient.post(Api.Tasks()) {
      contentType(ContentType.Application.Json)
      setBody(request)
    }
    checkSuccess(response)
    val task = createRemoteTask(response.body())
    return taskMutex.withLock {
      postedSinceSnapshot += task.taskId
      // SSE may already have delivered newer progress or completion before this POST returns.
      taskMap[task.taskId] ?: addOrUpdate(task)
    }
  }

  override suspend fun resolve(input: ResolveInput): ResolvedSource = when (input) {
    is ResolveInput.Url -> resolveUrl(input)
    is ResolveInput.Content -> resolveContent(input)
  }

  private suspend fun resolveUrl(input: ResolveInput.Url): ResolvedSource {
    val response = httpClient.post(Api.Resolve()) {
      contentType(ContentType.Application.Json)
      setBody(ResolveUrlRequest(input.url, input.headers))
    }
    // Older servers answer these with 500, which stays a RemoteApiException.
    resolveRefusal(response)?.let { throw it }
    checkSuccess(response)
    return response.body()
  }

  private suspend fun resolveContent(input: ResolveInput.Content): ResolvedSource {
    val response = httpClient.post(Api.Resolve.Content(fileName = input.fileName)) {
      contentType(ContentType.Application.OctetStream)
      setBody(input.bytes)
    }
    resolveRefusal(response)?.let { throw it }
    when (response.status) {
      HttpStatusCode.PayloadTooLarge -> throw IllegalArgumentException("File is too large")
      HttpStatusCode.NotFound, HttpStatusCode.NotImplemented ->
        throw UnsupportedOperationException("Resolving file content is unavailable")
    }
    checkSuccess(response)
    return response.body()
  }

  /**
   * The error a resolve the server refused for its input throws, as [KetchApi.resolve] does: 415
   * `unsupported` for one no source takes, or that its source lacks something for, with the
   * [cause][unsupportedCause] that tells them apart, and 422 with a source's own, decoded with its
   * reason. Other bodies, such as a 415 for a request body that is not JSON or a proxy's page, are
   * a [RemoteApiException].
   */
  private suspend fun resolveRefusal(response: HttpResponse): Exception? =
    when (response.status) {
      HttpStatusCode.UnsupportedMediaType -> {
        val body = response.errorResponse()
        if (body?.error == "unsupported") {
          KetchError.Unsupported(unsupportedCause(body.message))
        } else {
          response.toRemoteApiException(body)
        }
      }
      HttpStatusCode.UnprocessableEntity ->
        typedError(response.bodyAsText()) ?: response.toRemoteApiException()
      else -> null
    }

  /**
   * Why the server refused an input with 415 `unsupported` and [message]: a
   * [NoDownloadSourceException] when no source takes it, which Ketch servers word "No download
   * source ...", else what the source that takes it lacks, such as a usable proxy: the reason
   * after the server's "Not supported: ", or none when it gave none.
   */
  private fun unsupportedCause(message: String): Exception {
    if (message.startsWith("No download source")) return NoDownloadSourceException()
    val reason = message.removePrefix("Not supported").removePrefix(":").trim()
    return IllegalStateException(reason.ifEmpty { null })
  }

  /** The [KetchError] [body] holds, or `null` when it is no JSON object with a `type`. */
  private fun typedError(body: String): KetchError? {
    val element = try {
      json.parseToJsonElement(body)
    } catch (e: SerializationException) {
      return null
    }
    if (element !is JsonObject || "type" !in element) return null
    return json.decodeFromJsonElement(KetchError.tolerantSerializer(), element)
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

  /** The server's [KetchStatus.features], asked once per connection. */
  private suspend fun features(): Set<String> {
    val connection = connectionGeneration.value
    featuresMutex.withLock {
      cachedFeatures?.takeIf { it.first == connection }?.let { return it.second }
    }
    val features = status().features
    featuresMutex.withLock { cachedFeatures = connection to features }
    return features
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
  private suspend fun requireProxySupport(proxy: Proxy?) {
    if (proxy == null || proxy == Proxy.System) return
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

  override suspend fun selectNetworkInterfaces(config: NetworkInterfaceConfig): NetworkInterfaces {
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

  /**
   * Follows [interest] on the server, about every [interval]. While [start] keeps the event
   * stream of a server that serves it open, every interest comes on that stream: segments as
   * updates of [tasks], others as values the server encodes with [Interest.valueSerializer]; an
   * interest the server cannot serve fails with [UnsupportedOperationException]. Otherwise
   * [Interest.Segments], [Interest.Stats], [Interest.Connections] and `TorrentInterest` come from
   * their own endpoints or from [tasks], and others fail with [UnsupportedOperationException]
   * when collected, as do interests whose type `StreamSerializers` does not name.
   *
   * On older servers, live connections fail with [UnsupportedOperationException] when the
   * server's [KetchStatus.features] lack [KetchFeatures.ACTIVE_CONNECTIONS], before anything is
   * asked for. Interests other than segments and stats end with a failure when the connection to
   * the server drops or the server ends the stream; they do not reconnect, so collect them again
   * to resume. Stats keep their last value while the server is unreachable.
   */
  @Suppress("UNCHECKED_CAST")
  override fun <T> subscribe(interest: Interest<T>, interval: Duration): Flow<T> {
    require(interval.isPositive()) { "interval must be positive" }
    return when (interest) {
      is Interest.Segments -> followSegments(interest, interval)
      Interest.Stats -> followStats(interval)
      is Interest.Connections -> followConnections(interest, interval)
      is TorrentInterest -> torrents.observe(interest.taskId, interval)
      else -> followInterest(interest, interval)
    } as Flow<T>
  }

  /** The segments of the tasks [interest] names, as the stream updates their tasks. */
  @OptIn(ExperimentalCoroutinesApi::class)
  private fun followSegments(
    interest: Interest.Segments,
    interval: Duration,
  ): Flow<Map<String, List<Segment>>> {
    val segments = tasks.flatMapLatest { all ->
      val named = all.filter { it.taskId in interest.taskIds }
      if (named.isEmpty()) {
        flowOf(emptyMap())
      } else {
        combine(named.map { task -> task.segments.map { task.taskId to it } }) { it.toMap() }
      }
    }.distinctUntilChanged()
    return flow { interests.holding(interest, interval) { emitAll(segments) } }
  }

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun followStats(interval: Duration): Flow<KetchStats> {
    // Older servers send every change of every task, so their totals are computed here.
    val computed = flow {
      while (true) {
        emit(KetchStats.of(tasks.value.map { it.state.value }))
        delay(interval)
      }
    }
    return streamed.flatMapLatest { streamed ->
      if (!streamed) {
        computed
      } else {
        // The last stats stay until a new stream sends some.
        streamInterest(Interest.Stats, interval)
          .retryWhen { cause, _ -> cause is StreamLostException }
          .mapInterestFailures()
      }
    }.distinctUntilChanged()
  }

  private fun followConnections(
    interest: Interest.Connections,
    interval: Duration,
  ): Flow<ActiveConnections> = flow {
    if (streamed.value) {
      emitAll(streamInterest(interest, interval).distinctUntilChanged().mapInterestFailures())
      return@flow
    }
    if (KetchFeatures.ACTIVE_CONNECTIONS !in features()) {
      throw UnsupportedOperationException("This device does not report its connections")
    }
    emitAll(httpClient.activeConnectionEvents(interest.limit, json))
  }

  /** An interest only the event stream serves. */
  private fun <T> followInterest(interest: Interest<T>, interval: Duration): Flow<T> = flow {
    // The subscription could not be sent with an interest it cannot encode.
    @OptIn(ExperimentalSerializationApi::class)
    val encodable = json.serializersModule.getPolymorphic(Interest::class, interest) != null
    if (!streamed.value || !encodable) {
      throw UnsupportedOperationException("This client does not follow $interest")
    }
    emitAll(streamInterest(interest, interval).mapInterestFailures())
  }

  /** Fails with what callers of [subscribe] expect when the server fails the interest. */
  private fun <T> Flow<T>.mapInterestFailures(): Flow<T> = catch { error ->
    throw if (error is InterestFailedException) error.error.toException() else error
  }

  override fun close() {
    log.d { "Close" }
    streamed.value = false
    try {
      scope.cancel()
    } finally {
      httpClient.close()
    }
  }

  // -- Event connection with reconnection --

  private suspend fun connectSse() {
    var reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
    var failures = 0
    val connected = {
      _connectionState.value = ConnectionState.Connected
      reconnectDelayMs = INITIAL_RECONNECT_DELAY_MS
      failures = 0
    }
    while (true) {
      try {
        if (servesStream()) {
          streamed.value = true
          readStream(onConnected = connected)
        } else {
          streamed.value = false
          fetchAllTasks()
          connected()
          readEvents()
        }
      } catch (_: UnauthorizedException) {
        log.w { "Server $baseUrl rejected the API token (HTTP 401); not reconnecting" }
        streamed.value = false
        topics.closed()
        _connectionState.value = ConnectionState.Unauthorized
        return
      } catch (error: Exception) {
        // The stack trace is printed once per outage, not on every reconnect attempt.
        failures++
        log.w(error.takeIf { failures == 1 }) {
          "Connection to $baseUrl failed (attempt $failures): ${error.describeCauses()}"
        }
      }

      topics.closed()
      _connectionState.value = ConnectionState.Disconnected()

      log.d { "Reconnecting to $baseUrl in ${reconnectDelayMs}ms" }
      delay(reconnectDelayMs)
      _connectionState.value = ConnectionState.Connecting
      reconnectDelayMs = (reconnectDelayMs * 2)
        .coerceAtMost(MAX_RECONNECT_DELAY_MS)
    }
  }

  /** Whether the server lists the event stream; one without a status route does not. */
  private suspend fun servesStream(): Boolean {
    val response = httpClient.get(Api.Status())
    if (response.status == HttpStatusCode.Unauthorized) throw UnauthorizedException()
    if (response.status == HttpStatusCode.NotFound) return false
    checkSuccess(response)
    return StreamEvents.FEATURE in response.body<KetchStatus>().features
  }

  /** Reads the task events of a server without the event stream, `/api/events`. */
  private suspend fun readEvents() {
    httpClient.sse(urlString = "/api/events") {
      connectionGeneration.update { it + 1 }
      log.i { "Connected to $baseUrl/api/events" }
      incoming.collect { sseEvent ->
        val data = sseEvent.data ?: return@collect
        try {
          val taskEvent: TaskEvent = json.decodeFromString(data)
          handleEvent(taskEvent)
        } catch (error: Exception) {
          log.e(error) { "Failed to handle event" }
        }
      }
    }
  }

  /**
   * Reads the event stream, `/api/stream`, until it ends, sending the callers' subscription each
   * time it changes. [onConnected] runs once the stream's task snapshot is applied. The stream is
   * read as plain text, which any engine can serve.
   */
  private suspend fun readStream(onConnected: () -> Unit) {
    httpClient.prepareGet(Api.Stream()) {
      header(HttpHeaders.Accept, "text/event-stream")
    }.execute { response ->
      if (response.status == HttpStatusCode.Unauthorized) throw UnauthorizedException()
      checkSuccess(response)
      coroutineScope {
        var subscriber: Job? = null
        try {
          response.bodyAsChannel().readServerSentEvents { name, data ->
            when (name) {
              StreamEvents.HELLO -> {
                val hello = json.decodeFromString<StreamHello>(data)
                val generation = connectionGeneration.updateAndGet { it + 1 }
                topics.opened(generation)
                log.i { "Connected to $baseUrl/api/stream" }
                subscriber?.cancel()
                subscriber = launch { sendSubscriptions(hello.streamId) }
              }
              StreamEvents.TASKS -> {
                applyStreamSnapshot(json.decodeFromString<TasksResponse>(data).tasks)
                onConnected()
              }
              StreamEvents.ERROR -> {
                val error = json.decodeFromString<StreamError>(data)
                throw IllegalStateException("The server ended the event stream: ${error.error}")
              }
              else -> try {
                handleStreamEvent(name, data)
              } catch (error: CancellationException) {
                throw error
              } catch (error: Exception) {
                log.e(error) { "Failed to handle stream event $name" }
              }
            }
          }
        } finally {
          subscriber?.cancel()
        }
      }
    }
    log.i { "The event stream of $baseUrl ended" }
  }

  /** Sends the callers' subscription to the stream with [streamId] each time it changes. */
  @OptIn(FlowPreview::class)
  private suspend fun sendSubscriptions(streamId: String) {
    var revision = 0L
    // The first goes out at once; later changes wait for others that follow right after.
    interests.subscriptions.debounce { if (revision == 0L) 0 else SUBSCRIPTION_DEBOUNCE_MS }
      .collect { wanted ->
        revision++
        try {
          val response = httpClient.put(
            Api.Stream.ById.Subscription(Api.Stream.ById(id = streamId)),
          ) {
            contentType(ContentType.Application.Json)
            setBody(wanted.copy(revision = revision))
          }
          // The stream applies it with its `subscribed` event; one that closed is reconnected
          // by the stream loop.
          if (!response.status.isSuccess()) {
            log.w { "Event stream refused a subscription (HTTP ${response.status.value})" }
          }
        } catch (error: CancellationException) {
          throw error
        } catch (error: Exception) {
          log.w { "Failed to send the event subscription: ${error.describeCauses()}" }
        }
      }
  }

  internal suspend fun handleStreamEvent(name: String, data: String) {
    when (name) {
      StreamEvents.TASK_ADDED -> {
        val task = createRemoteTask(json.decodeFromString<TaskSnapshot>(data))
        taskMutex.withLock { addOrUpdate(task) }
      }
      StreamEvents.TASK_REMOVED -> {
        val taskId = json.decodeFromString<StreamTaskRef>(data).taskId
        taskMutex.withLock { removeTask(taskId) }
      }
      StreamEvents.TASK_STATE -> {
        val update = json.decodeFromString<TaskStateUpdate>(data)
        taskMutex.withLock {
          val task = taskMap[update.taskId] ?: return
          task.applyEvent(update.state, update.request, null, update.queuePosition)
          taskRevision++
        }
      }
      StreamEvents.TASK_PROGRESS -> {
        val update = json.decodeFromString<TaskProgressUpdate>(data)
        taskMutex.withLock { taskMap[update.taskId]?.applyProgress(update.progress) }
      }
      StreamEvents.TASK_SEGMENTS -> {
        val update = json.decodeFromString<TaskSegmentsUpdate>(data)
        taskMutex.withLock { taskMap[update.taskId]?.applySegments(update.segments) }
      }
      StreamEvents.INTEREST -> {
        val update = json.decodeFromString<InterestUpdate>(data)
        topics.value(update.id, update.value)
      }
      StreamEvents.INTEREST_END -> {
        topics.ended(json.decodeFromString<InterestRef>(data).id, TopicEnd.Completed)
      }
      StreamEvents.INTEREST_ERROR -> {
        val error = json.decodeFromString<InterestError>(data)
        log.d { "Event stream interest ${error.id} failed: ${error.error}" }
        topics.ended(error.id, TopicEnd.Failed(error))
      }
      StreamEvents.SUBSCRIBED -> {
        topics.applied(json.decodeFromString<StreamSubscribed>(data).subscription)
        log.d { "Event stream subscription applied" }
      }
      else -> log.v { "Ignoring stream event $name" }
    }
  }

  /**
   * Replaces the task list with the event stream's snapshot. Tasks this client added that the
   * snapshot lacks may have been added after it; they stay until the server says otherwise.
   */
  internal suspend fun applyStreamSnapshot(snapshots: List<TaskSnapshot>) {
    val tasks = snapshots.map(::createRemoteTask)
    val unconfirmed = taskMutex.withLock {
      val ids = tasks.mapTo(HashSet()) { it.taskId }
      val posted = postedSinceSnapshot - ids
      postedSinceSnapshot.clear()
      taskMap.keys.retainAll(ids + posted)
      tasks.forEach { addOrUpdate(it) }
      taskRevision++
      _tasks.value = taskMap.values.toList()
      posted
    }
    if (unconfirmed.isNotEmpty()) scope.launch { confirmTasks(unconfirmed) }
  }

  /** Removes the tasks of [taskIds] the server no longer has. */
  private suspend fun confirmTasks(taskIds: Set<String>) {
    for (taskId in taskIds) {
      try {
        val response = httpClient.get(Api.Tasks.ById(id = taskId))
        if (response.status == HttpStatusCode.NotFound) {
          taskMutex.withLock { removeTask(taskId) }
        }
      } catch (error: CancellationException) {
        throw error
      } catch (error: Exception) {
        log.d { "Failed to confirm taskId=$taskId: ${error.describeCauses()}" }
      }
    }
  }

  /**
   * The values the event stream sends for [interest], ending when the server ends it; fails with
   * [InterestFailedException] when the server fails it, and with [StreamLostException] when the
   * stream closes.
   */
  private fun <T> streamInterest(interest: Interest<T>, interval: Duration): Flow<T> = flow {
    val generation = topics.live.filterNotNull().first()
    interests.holding(interest, interval) { id ->
      var emitted = 0L
      combine(topics.live, topics.states.map { it[id] }, ::Pair)
        .distinctUntilChanged()
        .transformWhile { (live, state) ->
          if (live != generation) throw StreamLostException()
          val value = state?.value
          if (value != null && state.sequence > emitted) {
            emitted = state.sequence
            emit(json.decodeFromJsonElement(interest.valueSerializer, value))
          }
          when (val end = state?.end) {
            null -> true
            TopicEnd.Completed -> false
            is TopicEnd.Failed -> throw InterestFailedException(end.error)
          }
        }
        .collect { emit(it) }
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
    /** How often every downloading task's progress updates unless set otherwise. */
    val DEFAULT_PROGRESS_INTERVAL: Duration = 1.seconds

    private const val INITIAL_RECONNECT_DELAY_MS = 1000L
    private const val MAX_RECONNECT_DELAY_MS = 30_000L
    private const val SUBSCRIPTION_DEBOUNCE_MS = 50L
  }
}
