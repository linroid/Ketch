package com.linroid.kdown.app.backend

import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.DownloadTask
import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.api.KDownVersion
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.remote.ConnectionState
import com.linroid.kdown.remote.RemoteKDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * Manages the list of configured backends, the currently active
 * backend, and lifecycle transitions.
 *
 * When [BackendFactory.hasEmbedded] is `true` (Android, iOS,
 * JVM/Desktop), an embedded [KDown] instance is created once
 * and reused. An optional HTTP server can be started/stopped
 * to expose the same instance over the network.
 *
 * When [BackendFactory.hasEmbedded] is `false` (wasmJs/web),
 * the manager starts with a placeholder and only remote
 * backends are available.
 */
class BackendManager(
  private val factory: BackendFactory,
) {
  private val scope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default
  )

  /**
   * Single embedded KDown instance, alive for the whole app.
   * `null` in remote-only mode (e.g. wasmJs/web).
   */
  private val embeddedApi: KDownApi? =
    if (factory.hasEmbedded) factory.createEmbedded() else null

  private val embeddedEntry: BackendEntry? = embeddedApi?.let {
    BackendEntry(
      id = "embedded",
      label = "Embedded",
      config = BackendConfig.Embedded,
      connectionState = MutableStateFlow(
        ConnectionState.Connected
      )
    )
  }

  private val _backends =
    MutableStateFlow(listOfNotNull(embeddedEntry))
  val backends: StateFlow<List<BackendEntry>> =
    _backends.asStateFlow()

  private val _activeBackend =
    MutableStateFlow(embeddedEntry)
  val activeBackend: StateFlow<BackendEntry?> =
    _activeBackend.asStateFlow()

  private val _activeApi =
    MutableStateFlow(embeddedApi ?: DisconnectedApi)
  val activeApi: StateFlow<KDownApi> = _activeApi.asStateFlow()

  val isLocalServerSupported: Boolean =
    factory.isLocalServerSupported

  private val _serverState =
    MutableStateFlow<ServerState>(ServerState.Stopped)
  /** State of the optional HTTP server for the embedded backend. */
  val serverState: StateFlow<ServerState> =
    _serverState.asStateFlow()

  init {
    embeddedApi?.let { api ->
      scope.launch { api.start() }
    }
  }

  private var connectionObserverJob: Job? = null

  /**
   * Switch to a different configured backend by ID.
   *
   * Switching to Remote creates a new [RemoteKDown] client.
   * Switching back to Embedded reuses the original instance.
   * The local server (if running) is not affected by switching.
   */
  suspend fun switchTo(id: String) {
    if (id == _activeBackend.value?.id) return
    val entry = _backends.value.find { it.id == id }
      ?: throw IllegalArgumentException(
        "Backend not found: $id"
      )

    // Clean up current connection observer
    connectionObserverJob?.cancel()
    connectionObserverJob = null

    // Close remote client if we're leaving one
    val oldApi = _activeApi.value
    if (oldApi !== embeddedApi && oldApi !== DisconnectedApi) {
      oldApi.close()
    }

    // Set up new backend
    when (val config = entry.config) {
      is BackendConfig.Embedded -> {
        _activeApi.value = embeddedApi ?: DisconnectedApi
      }
      is BackendConfig.Remote -> {
        _activeApi.value =
          factory.createRemote(config)
      }
    }

    _activeBackend.value = entry

    // Post-switch initialization
    val newApi = _activeApi.value
    if (newApi is RemoteKDown) {
      observeRemoteConnectionState(entry, newApi)
    }
    newApi.start()
    if (newApi !is RemoteKDown) {
      (entry.connectionState as? MutableStateFlow)?.value =
        ConnectionState.Connected
    }
  }

  /**
   * Start the local HTTP server exposing the embedded backend.
   * Only available when [isLocalServerSupported] is `true`.
   */
  fun startServer(port: Int = 8642, token: String? = null) {
    val api = embeddedApi
      ?: throw UnsupportedOperationException(
        "No embedded backend for local server"
      )
    factory.stopServer()
    factory.startServer(port, token, api)
    _serverState.value = ServerState.Running(port)
  }

  /** Stop the local HTTP server if running. */
  fun stopServer() {
    factory.stopServer()
    _serverState.value = ServerState.Stopped
  }

  /**
   * Add a remote server to the backend list.
   * Does NOT activate it -- call [switchTo] afterward.
   */
  @OptIn(ExperimentalUuidApi::class)
  fun addRemote(
    host: String,
    port: Int = 8642,
    token: String? = null,
  ): BackendEntry {
    val config = BackendConfig.Remote(host, port, token)
    val entry = BackendEntry(
      id = Uuid.random().toString(),
      label = "$host:$port",
      config = config,
      connectionState = MutableStateFlow(
        ConnectionState.Disconnected()
      )
    )
    _backends.value += entry
    return entry
  }

  /**
   * Remove a backend by ID. Cannot remove the embedded backend.
   * If the removed backend is active, switches to embedded (or
   * falls back to disconnected in remote-only mode).
   */
  suspend fun removeBackend(id: String) {
    require(id != "embedded") {
      "Cannot remove the embedded backend"
    }
    if (_activeBackend.value?.id == id) {
      if (embeddedEntry != null) {
        switchTo("embedded")
      } else {
        // Remote-only mode: go back to disconnected
        connectionObserverJob?.cancel()
        connectionObserverJob = null
        val oldApi = _activeApi.value
        if (oldApi !== DisconnectedApi) {
          oldApi.close()
        }
        _activeApi.value = DisconnectedApi
        _activeBackend.value = null
      }
    }
    _backends.value = _backends.value.filter { it.id != id }
  }

  /** Close the active backend and release all resources. */
  fun close() {
    val currentApi = _activeApi.value
    if (currentApi !== embeddedApi &&
      currentApi !== DisconnectedApi
    ) {
      currentApi.close()
    }
    factory.stopServer()
    embeddedApi?.close()
    scope.cancel()
  }

  private fun observeRemoteConnectionState(
    entry: BackendEntry,
    remote: RemoteKDown,
  ) {
    val entryState =
      entry.connectionState as? MutableStateFlow ?: return
    connectionObserverJob = scope.launch {
      remote.connectionState.collect { entryState.value = it }
    }
  }
}

/**
 * Placeholder [KDownApi] used when no backend is connected.
 * Returns empty tasks and a default version. Download requests
 * throw — the UI should prompt to add a remote server first.
 */
private object DisconnectedApi : KDownApi {
  override val backendLabel = "Not connected"
  override val tasks =
    MutableStateFlow(emptyList<DownloadTask>())
  override val version = MutableStateFlow(
    KDownVersion(KDownVersion.DEFAULT, KDownVersion.DEFAULT)
  )

  override suspend fun download(
    request: DownloadRequest,
  ): DownloadTask {
    throw IllegalStateException(
      "No backend connected. Add a remote server first."
    )
  }

  override suspend fun setGlobalSpeedLimit(limit: SpeedLimit) {}
  override suspend fun start() {}
  override fun close() {}
}
