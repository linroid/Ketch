package com.linroid.ketch.app.instance

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.config.ServerConfig
import com.linroid.ketch.config.TorrentSettings
import com.linroid.ketch.remote.ConnectionState
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.device_server_start_failed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.CoroutineContext
import kotlin.time.Clock

/**
 * Manages the list of configured instances, the currently active
 * instance, and lifecycle transitions.
 *
 * When [InstanceFactory.hasEmbedded] is `true` (Android, iOS,
 * JVM/Desktop), an embedded [KetchApi] instance is created once
 * and reused. An optional HTTP server can be started/stopped
 * to expose the same instance over the network.
 *
 * When [InstanceFactory.hasEmbedded] is `false` (wasmJs/web),
 * the manager starts with a placeholder and only remote
 * instances are available.
 *
 * Remote devices stay connected under [keepAlive]: the active one always, and the first
 * [KeepAlivePolicy.maxWatched] devices whose [RemoteConfig.watch] is set while the app is in
 * front, so switching to them is instant. A device that leaves that set is disconnected, and
 * connecting to it again uses a fresh client from [InstanceFactory.createRemote]. The device
 * that was active when the app last ran is active again at launch.
 *
 * @param keepAlive which remote devices stay connected besides the active one.
 * @param context dispatcher of the coroutines that connect devices and follow them; a [Job] in
 *   it becomes the parent of those coroutines, and cancelling it stops them like [close].
 * @param clock current time of [presence].
 */
@OptIn(ExperimentalCoroutinesApi::class)
class InstanceManager(
  private val factory: InstanceFactory,
  initialRemotes: List<RemoteConfig> = emptyList(),
  val configStore: ConfigStore? = null,
  private val keepAlive: KeepAlivePolicy = KeepAlivePolicy(),
  context: CoroutineContext = Dispatchers.Default,
  private val clock: Clock = Clock.System,
) {
  private val log = KetchLogger("InstanceManager")
  private val scope = CoroutineScope(context + SupervisorJob(context[Job]))

  /**
   * Single embedded instance, alive for the whole app.
   * `null` in remote-only mode (e.g. wasmJs/web).
   */
  private val embeddedInstance: EmbeddedInstance? =
    if (factory.hasEmbedded) factory.createEmbedded() else null

  /**
   * The embedded [KetchApi], whether or not it is the active instance.
   * `null` in remote-only mode (e.g. wasmJs/web).
   */
  val embedded: KetchApi? get() = embeddedInstance?.instance

  private val _instances =
    MutableStateFlow(listOfNotNull<InstanceEntry>(embeddedInstance))

  /** Every configured device: the embedded one first, then the remote ones in saved order. */
  val instances: StateFlow<List<InstanceEntry>> =
    _instances.asStateFlow()

  private val _activeInstance =
    MutableStateFlow<InstanceEntry?>(embeddedInstance)

  /** The device the app shows and adds downloads to; `null` on the web until one is added. */
  val activeInstance: StateFlow<InstanceEntry?> =
    _activeInstance.asStateFlow()

  private val _activeApi =
    MutableStateFlow(
      embeddedInstance?.instance ?: DisconnectedApi,
    )

  /** The [activeInstance]'s engine or client; a placeholder that adds nothing without one. */
  val activeApi: StateFlow<KetchApi> = _activeApi.asStateFlow()

  private val _deviceScope =
    MutableStateFlow<DeviceScope>(DeviceScope.Single(LOCAL_DEVICE_ID))

  /** Whether the app shows the active device or every device; see [showAllDevices]. */
  val deviceScope: StateFlow<DeviceScope> = _deviceScope.asStateFlow()

  /** Whether the embedded device can be shared over the network; see [startServer]. */
  val isLocalServerSupported: Boolean =
    factory.isLocalServerSupported

  private val _serverState =
    MutableStateFlow<ServerState>(ServerState.Stopped)

  /** State of the optional HTTP server for the embedded instance. */
  val serverState: StateFlow<ServerState> =
    _serverState.asStateFlow()

  private val inForeground = MutableStateFlow(true)
  private val backgroundExpired = MutableStateFlow(false)
  private val localSpeedMode = MutableStateFlow<Flow<SpeedMode>>(flowOf(SpeedMode.Full))

  // Clients started since they were created, by device id, with the jobs that learn their
  // names. Both change only under connectionLock.
  private val connectionLock = Mutex()
  private val started = mutableMapOf<String, KetchApi>()
  private val naming = mutableMapOf<String, Job>()
  private val connected = MutableStateFlow<Set<String>>(emptySet())

  /**
   * What each configured device is doing, in the order of [instances]. Built on first use; it
   * reads each online device's status every 30 seconds.
   */
  val presence: StateFlow<List<DevicePresence>> by lazy {
    DevicePresenceModel(
      devices = instances,
      connected = connected,
      serverState = serverState,
      shown = deviceScope,
      inForeground = inForeground,
      localMode = localSpeedMode.flatMapLatest { it },
      scope = scope,
      clock = clock,
    ).presence
  }

  init {
    val saved = configStore?.load()
    for (remote in initialRemotes) add(remote)
    restoreActive(saved?.ui?.lastDeviceId)
    embeddedInstance?.instance?.let { ketch ->
      scope.launch { ketch.start() }
    }
    if (isLocalServerSupported && saved?.server?.autoStart == true) {
      startServer()
    }
    scope.launch {
      combine(_instances, _activeInstance, backgroundExpired) { _, _, _ -> }
        .conflate()
        .collect { connectionLock.withLock { reconcile() } }
    }
    scope.launch {
      inForeground.collectLatest { front ->
        if (front) {
          backgroundExpired.value = false
        } else {
          val grace = keepAlive.backgroundGrace ?: return@collectLatest
          delay(grace)
          log.i { "In the background for $grace; disconnecting the devices not shown" }
          backgroundExpired.value = true
        }
      }
    }
    scope.launch { appForegroundChanges().collect { setInForeground(it) } }
  }

  /**
   * Switch to a different configured instance, and show it alone.
   *
   * Switching only changes the active device, so it is instant for a connected one: the device
   * shown before stays connected while it is watched, and only one that is not watched
   * disconnects. A remote device that was not connected connects with a fresh client.
   * The local server (if running) is not affected by switching.
   */
  suspend fun switchTo(instance: InstanceEntry) {
    connectionLock.withLock {
      val deviceId = instance.deviceId
      val target = requireNotNull(entryOf(deviceId)) { "Instance not found: ${instance.label}" }
      _deviceScope.value = DeviceScope.Single(deviceId)
      if (deviceId == _activeInstance.value?.deviceId) return
      _activeInstance.value = target
      _activeApi.value = target.instance
      reconcile()
    }
  }

  /**
   * Shows every device at once, keeping the active one as the target of new downloads. Does
   * nothing and returns `false` with fewer than [DeviceScope.MIN_DEVICES] devices.
   */
  fun showAllDevices(): Boolean {
    if (_instances.value.size < DeviceScope.MIN_DEVICES) return false
    _deviceScope.value = DeviceScope.All
    return true
  }

  /**
   * Tells the manager whether the app is in front. [KeepAlivePolicy.backgroundGrace] after it
   * leaves, the remote devices other than the active one disconnect; they connect again when
   * it returns. The mobile hosts call it; iOS also reports it on its own.
   */
  fun setInForeground(inForeground: Boolean) {
    this.inForeground.value = inForeground
  }

  /** Feeds the embedded device's speed mode, owned by the host, to [presence]. */
  fun setLocalSpeedMode(mode: Flow<SpeedMode>) {
    localSpeedMode.value = mode
  }

  /**
   * Start the local HTTP server exposing the embedded instance, with the
   * server settings currently saved in [configStore].
   * Only available when [isLocalServerSupported] is `true`.
   */
  fun startServer() {
    val api = embeddedInstance?.instance
      ?: throw UnsupportedOperationException("No embedded instance for local server")
    val config = configStore?.load()?.server ?: ServerConfig()
    factory.stopServer()
    _serverState.value = try {
      factory.startServer(api)
      ServerState.Running(config)
    } catch (e: Exception) {
      // Typically the port is taken; report it instead of crashing.
      ServerState.Failed(
        e.message?.let(::verbatim) ?: Res.string.device_server_start_failed.text(),
      )
    }
  }

  /** Stop the local HTTP server if running. */
  fun stopServer() {
    factory.stopServer()
    _serverState.value = ServerState.Stopped
  }

  /**
   * Applies saved torrent [settings] to the embedded instance; torrents
   * pick them up as they start or resume. Does nothing when the embedded
   * instance has no torrent support, or there is none.
   */
  suspend fun applyTorrentSettings(settings: TorrentSettings) {
    factory.applyTorrentSettings?.invoke(settings)
  }

  /**
   * Replaces [old] with a new remote instance that reaches it over HTTPS when [secure] or HTTP
   * otherwise, with [token] when it brings one or the code it has, then starts the connection.
   */
  suspend fun reconnectWith(old: RemoteInstance, secure: Boolean, token: String? = null) {
    reconnect(old) { it.copy(secure = secure, apiToken = token ?: it.apiToken) }
    persistRemotes()
  }

  /**
   * Connects to [device] again with a fresh client, such as after it rejected the token or to
   * retry an offline device now rather than at its next scheduled attempt. A device the app does
   * not keep connected only gets the fresh client.
   */
  suspend fun reconnect(device: RemoteInstance) {
    reconnect(device) { it }
  }

  /**
   * Adds the remote device [config] describes to the instance list.
   * Does NOT activate it -- call [switchTo] afterward. A watched device connects at once.
   *
   * [RemoteConfig.name] is the device's name, such as the one a [DiscoveredServer] announces;
   * generic and blank names are dropped (see [deviceNameOrNull]).
   *
   * @return the new device, or the one already configured at its host and port.
   */
  fun addRemote(config: RemoteConfig): RemoteInstance {
    val entry = add(config)
    persistRemotes()
    return entry
  }

  /**
   * Names [device] [name]; a blank name clears it, and the device shows as `host:port` until
   * it announces a name of its own on its next connection.
   */
  fun rename(device: RemoteInstance, name: String) {
    val clean = name.trim().ifEmpty { null }
    updateRemote(device.deviceId) { it.copy(remoteConfig = it.remoteConfig.copy(name = clean)) }
    persistRemotes()
  }

  /**
   * Sets whether the app stays connected to [device] while another device shows. Turning it
   * off disconnects the device unless it is the active one.
   */
  fun setWatched(device: RemoteInstance, watch: Boolean) {
    updateRemote(device.deviceId) { it.copy(remoteConfig = it.remoteConfig.copy(watch = watch)) }
    persistRemotes()
  }

  /**
   * Remove an instance. Cannot remove the embedded instance.
   * If the removed instance is active, switches to embedded
   * (or falls back to disconnected in remote-only mode).
   * Its client is closed.
   */
  suspend fun removeInstance(instance: InstanceEntry) {
    require(instance !is EmbeddedInstance) { "Cannot remove the embedded instance" }
    val deviceId = instance.deviceId
    if (_activeInstance.value?.deviceId == deviceId) {
      if (embeddedInstance != null) {
        switchTo(embeddedInstance)
      } else {
        // Remote-only mode: go back to disconnected
        _activeApi.value = DisconnectedApi
        _activeInstance.value = null
      }
    }
    connectionLock.withLock {
      val removed = entryOf(deviceId) ?: return
      _instances.update { list -> list.filterNot { it.deviceId == deviceId } }
      forget(deviceId)
      removed.instance.close()
      reconcile()
    }
    val shown = _deviceScope.value
    if (_instances.value.size < DeviceScope.MIN_DEVICES || shown == DeviceScope.Single(deviceId)) {
      _deviceScope.value = DeviceScope.Single(_activeInstance.value?.deviceId ?: LOCAL_DEVICE_ID)
    }
    persistRemotes()
  }

  /** Close every instance and release all resources. */
  fun close() {
    scope.cancel()
    factory.stopServer()
    _instances.value.filterIsInstance<RemoteInstance>().forEach { it.instance.close() }
    embeddedInstance?.instance?.close()
  }

  private fun entryOf(deviceId: String): InstanceEntry? =
    _instances.value.firstOrNull { it.deviceId == deviceId }

  private fun add(config: RemoteConfig): RemoteInstance {
    val existing = entryOf("${config.host}:${config.port}")
    if (existing is RemoteInstance) return existing
    val entry = factory.createRemote(config.copy(name = deviceNameOrNull(config.name)))
    _instances.update { it + entry }
    return entry
  }

  private fun restoreActive(deviceId: String?) {
    val last = _instances.value.firstOrNull { it is RemoteInstance && it.deviceId == deviceId }
      ?: return
    _activeInstance.value = last
    _activeApi.value = last.instance
    _deviceScope.value = DeviceScope.Single(last.deviceId)
  }

  // Replaces the remote device with deviceId, keeping the active device pointing at it.
  private fun updateRemote(deviceId: String, transform: (RemoteInstance) -> RemoteInstance) {
    _instances.update { list ->
      list.map { if (it is RemoteInstance && it.deviceId == deviceId) transform(it) else it }
    }
    val active = _activeInstance.value ?: return
    val current = entryOf(active.deviceId) ?: return
    if (current !== active) {
      _activeInstance.value = current
      _activeApi.value = current.instance
    }
  }

  private suspend fun reconnect(device: RemoteInstance, config: (RemoteConfig) -> RemoteConfig) {
    connectionLock.withLock {
      val current = entryOf(device.deviceId) as? RemoteInstance ?: return
      val fresh = factory.createRemote(config(current.remoteConfig))
      updateRemote(current.deviceId) { fresh.copy(remoteConfig = config(it.remoteConfig)) }
      forget(current.deviceId)
      current.instance.close()
      reconcile()
    }
  }

  /** The remote devices to keep connected: the active one, then the watched ones. */
  private fun wanted(): Set<String> {
    val active = (_activeInstance.value as? RemoteInstance)?.deviceId
    if (backgroundExpired.value) return setOfNotNull(active)
    // The active device stays connected anyway, so it takes none of the watched slots.
    val watched = _instances.value
      .filter { it is RemoteInstance && it.remoteConfig.watch && it.deviceId != active }
      .take(keepAlive.maxWatched)
      .map { it.deviceId }
    return setOfNotNull(active) + watched
  }

  // Connects the wanted devices and disconnects the others. A disconnected device gets a fresh,
  // unstarted client at once, so the list never holds a closed one. Call under connectionLock.
  private suspend fun reconcile() {
    val wanted = wanted()
    for (entry in _instances.value.filterIsInstance<RemoteInstance>()) {
      val deviceId = entry.deviceId
      val client = started[deviceId]
      when {
        deviceId in wanted && client == null -> connect(entry)
        deviceId !in wanted && client === entry.instance -> {
          log.i { "Disconnecting from $deviceId, which is no longer kept connected" }
          val fresh = factory.createRemote(entry.remoteConfig)
          updateRemote(deviceId) {
            it.copy(instance = fresh.instance, connectionState = fresh.connectionState)
          }
          forget(deviceId)
          entry.instance.close()
        }
      }
    }
    connected.value = started.keys.toSet()
  }

  private suspend fun connect(entry: RemoteInstance) {
    val deviceId = entry.deviceId
    log.i { "Connecting to $deviceId" }
    started[deviceId] = entry.instance
    try {
      entry.instance.start()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Couldn't connect to $deviceId: ${e.describeCauses()}" }
    }
    if (entry.remoteConfig.name == null) naming[deviceId] = scope.launch { adoptName(entry) }
  }

  // Names a device after the name it announces once connected, unless it has one already.
  private suspend fun adoptName(entry: RemoteInstance) {
    entry.connectionState.first { it == ConnectionState.Connected }
    val status = try {
      entry.instance.status()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.d { "Couldn't read the name of ${entry.deviceId}: ${e.describeCauses()}" }
      return
    }
    val name = deviceNameOrNull(status.name) ?: return
    connectionLock.withLock {
      val unnamed = entryOf(entry.deviceId) as? RemoteInstance
      if (unnamed?.instance !== entry.instance || unnamed.remoteConfig.name != null) return
      log.i { "Naming ${entry.deviceId} after the name it announces" }
      // A rename on another thread may land meanwhile; it wins.
      updateRemote(entry.deviceId) { remote ->
        if (remote.remoteConfig.name != null) {
          remote
        } else {
          remote.copy(remoteConfig = remote.remoteConfig.copy(name = name))
        }
      }
      persistRemotes()
    }
  }

  private fun forget(deviceId: String) {
    started.remove(deviceId)
    naming.remove(deviceId)?.cancel()
    connected.value = started.keys.toSet()
  }

  private fun persistRemotes() {
    val store = configStore ?: return
    val remotes = _instances.value
      .filterIsInstance<RemoteInstance>()
      .map { it.remoteConfig }
    val current = store.load()
    store.save(current.copy(remotes = remotes))
  }
}

/**
 * Placeholder [KetchApi] used when no instance is connected.
 * Returns empty tasks. Download requests throw -- the UI should
 * prompt to add a remote server first.
 */
private object DisconnectedApi : KetchApi {
  override val backendLabel = "Not connected"
  override val tasks = MutableStateFlow(emptyList<DownloadTask>())

  override suspend fun download(request: DownloadRequest): DownloadTask = notConnected()

  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
    notConnected()

  override suspend fun status(): KetchStatus = notConnected()

  override suspend fun updateConfig(config: DownloadConfig) {}
  override suspend fun start() {}
  override fun close() {}

  private fun notConnected(): Nothing =
    throw IllegalStateException("No instance connected. Add a remote server first.")
}
