package com.linroid.kdown.app.instance

import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.DownloadTask
import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.api.KDownVersion
import com.linroid.kdown.api.ResolvedSource
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.core.KDown
import com.linroid.kdown.remote.RemoteKDown
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * Manages the list of configured instances, the currently active
 * instance, and lifecycle transitions.
 *
 * When [InstanceFactory.hasEmbedded] is `true` (Android, iOS,
 * JVM/Desktop), an embedded [KDown] instance is created once
 * and reused. An optional HTTP server can be started/stopped
 * to expose the same instance over the network.
 *
 * When [InstanceFactory.hasEmbedded] is `false` (wasmJs/web),
 * the manager starts with a placeholder and only remote
 * instances are available.
 */
class InstanceManager(
  private val factory: InstanceFactory,
) {
  private val scope = CoroutineScope(
    SupervisorJob() + Dispatchers.Default,
  )

  /**
   * Single embedded instance, alive for the whole app.
   * `null` in remote-only mode (e.g. wasmJs/web).
   */
  private val embeddedInstance: EmbeddedInstance? =
    if (factory.hasEmbedded) factory.createEmbedded() else null

  private val _instances =
    MutableStateFlow(listOfNotNull<InstanceEntry>(embeddedInstance))
  val instances: StateFlow<List<InstanceEntry>> =
    _instances.asStateFlow()

  private val _activeInstance =
    MutableStateFlow<InstanceEntry?>(embeddedInstance)
  val activeInstance: StateFlow<InstanceEntry?> =
    _activeInstance.asStateFlow()

  private val _activeApi =
    MutableStateFlow(
      embeddedInstance?.instance ?: DisconnectedApi,
    )
  val activeApi: StateFlow<KDownApi> = _activeApi.asStateFlow()

  val isLocalServerSupported: Boolean =
    factory.isLocalServerSupported

  private val _serverState =
    MutableStateFlow<ServerState>(ServerState.Stopped)

  /** State of the optional HTTP server for the embedded instance. */
  val serverState: StateFlow<ServerState> =
    _serverState.asStateFlow()

  init {
    embeddedInstance?.instance?.let { kdown ->
      scope.launch { kdown.start() }
    }
  }

  /**
   * Switch to a different configured instance.
   *
   * Switching to Remote creates a new [RemoteKDown] client.
   * Switching back to Embedded reuses the original instance.
   * The local server (if running) is not affected by switching.
   */
  suspend fun switchTo(instance: InstanceEntry) {
    if (instance == _activeInstance.value) return
    require(instance in _instances.value) {
      "Instance not found: ${instance.label}"
    }

    // Close remote client if we're leaving one
    val oldApi = _activeApi.value
    if (oldApi !== embeddedInstance?.instance &&
      oldApi !== DisconnectedApi
    ) {
      oldApi.close()
    }

    _activeApi.value = instance.instance
    _activeInstance.value = instance

    // Post-switch initialization
    instance.instance.start()
  }

  /**
   * Start the local HTTP server exposing the embedded instance.
   * Only available when [isLocalServerSupported] is `true`.
   */
  fun startServer(port: Int = 8642, token: String? = null) {
    val api = embeddedInstance?.instance
      ?: throw UnsupportedOperationException(
        "No embedded instance for local server",
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
   * Replace [old] with a new remote instance that uses
   * [token] for authentication, then start the connection.
   */
  suspend fun reconnectWithToken(
    old: RemoteInstance,
    token: String,
  ) {
    old.instance.close()
    val replacement = factory.createRemote(
      host = old.host,
      port = old.port,
      token = token,
    )
    _instances.value = _instances.value.map {
      if (it === old) replacement else it
    }
    if (_activeInstance.value === old) {
      _activeApi.value = replacement.instance
      _activeInstance.value = replacement
      replacement.instance.start()
    }
  }

  /**
   * Add a remote server to the instance list.
   * Does NOT activate it -- call [switchTo] afterward.
   */
  fun addRemote(
    host: String,
    port: Int = 8642,
    token: String? = null,
  ): RemoteInstance {
    val instance = factory.createRemote(host, port, token)
    _instances.value += instance
    return instance
  }

  /**
   * Remove an instance. Cannot remove the embedded instance.
   * If the removed instance is active, switches to embedded
   * (or falls back to disconnected in remote-only mode).
   */
  suspend fun removeInstance(instance: InstanceEntry) {
    require(instance !is EmbeddedInstance) {
      "Cannot remove the embedded instance"
    }
    if (_activeInstance.value == instance) {
      if (embeddedInstance != null) {
        switchTo(embeddedInstance)
      } else {
        // Remote-only mode: go back to disconnected
        val oldApi = _activeApi.value
        if (oldApi !== DisconnectedApi) {
          oldApi.close()
        }
        _activeApi.value = DisconnectedApi
        _activeInstance.value = null
      }
    }
    _instances.value = _instances.value.filter { it != instance }
  }

  /** Close the active instance and release all resources. */
  fun close() {
    val currentApi = _activeApi.value
    if (currentApi !== embeddedInstance?.instance &&
      currentApi !== DisconnectedApi
    ) {
      currentApi.close()
    }
    factory.stopServer()
    embeddedInstance?.instance?.close()
    scope.cancel()
  }
}

/**
 * Placeholder [KDownApi] used when no instance is connected.
 * Returns empty tasks and a default version. Download requests
 * throw -- the UI should prompt to add a remote server first.
 */
private object DisconnectedApi : KDownApi {
  override val backendLabel = "Not connected"
  override val tasks =
    MutableStateFlow(emptyList<DownloadTask>())
  override val version = MutableStateFlow(
    KDownVersion(KDownVersion.DEFAULT, KDownVersion.DEFAULT),
  )

  override suspend fun download(
    request: DownloadRequest,
  ): DownloadTask {
    throw IllegalStateException(
      "No instance connected. Add a remote server first.",
    )
  }

  override suspend fun resolve(
    url: String,
    headers: Map<String, String>,
  ): ResolvedSource {
    throw IllegalStateException(
      "No instance connected. Add a remote server first.",
    )
  }

  override suspend fun setGlobalSpeedLimit(limit: SpeedLimit) {}
  override suspend fun start() {}
  override fun close() {}
}
