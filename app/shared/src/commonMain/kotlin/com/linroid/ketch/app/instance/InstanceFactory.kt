package com.linroid.ketch.app.instance

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.config.TorrentSettings
import com.linroid.ketch.remote.RemoteKetch
import com.linroid.ketch.torrent.api.TorrentEngineSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/**
 * Creates [KetchApi] instances for each instance type.
 *
 * @param deviceName label for the embedded instance (e.g. device
 *   model on Android, hostname on desktop).
 * @param instanceId this device's identity, which its engine reports as
 *   [com.linroid.ketch.api.KetchStatus.instanceId], also while another process runs its
 *   downloads; a device that reports it is this one, which the apps never add. `null` where
 *   the device has none, as on the web.
 * @param embeddedFactory factory for creating the embedded [KetchApi]
 *   instance. When `null`, no embedded instance is available and
 *   [InstanceManager] starts in remote-only mode (e.g. wasmJs/web).
 *   Each platform provides its own factory that wires up the core
 *   engine with platform-specific dependencies.
 * @param localServerFactory optional factory that starts an HTTP
 *   server exposing the embedded [KetchApi], as [LocalServerContext] describes it.
 *   When non-null, server controls appear in the Embedded instance entry.
 *   Provided by Android and JVM/Desktop.
 * @param applyTorrentSettings applies changed torrent settings to the
 *   torrent source of the embedded instance, so they take effect without
 *   a restart. `null` when the embedded instance has no torrent support.
 * @param trackerList what each of the embedded instance's subscribed tracker lists holds; `null` when it
 *   has no torrent support.
 * @param refreshTrackerList downloads the subscribed tracker lists again now.
 * @param torrentEngineSettings the torrent settings the embedded instance runs, which other
 *   devices may change through its server; the app saves their changes. `null` when it has no
 *   torrent support.
 * @param uploadNetwork whether the device is on Wi-Fi or another unmetered network, as it
 *   changes. With [pauseUploads], [InstanceManager] pauses the embedded instance's uploads while
 *   it is not and [TorrentSettings.uploadWifiOnly] is on; `null` where uploads ignore the
 *   network, as on desktop.
 * @param pauseUploads pauses (`true`) or resumes the uploads of the embedded instance's torrents.
 * @param remoteFactory creates a new, unconnected client for a remote device; a [RemoteKetch]
 *   by default. [InstanceManager] calls it each time it connects to the device again.
 */
class InstanceFactory(
  val deviceName: String = "Embedded",
  val instanceId: String? = null,
  private val embeddedFactory: (() -> KetchApi)? = null,
  private val localServerFactory: ((LocalServerContext) -> LocalServerHandle)? = null,
  internal val applyTorrentSettings: (suspend (TorrentSettings) -> Unit)? = null,
  internal val trackerList: Flow<List<TrackerListStatus>>? = null,
  internal val refreshTrackerList: () -> Unit = {},
  internal val torrentEngineSettings: StateFlow<TorrentEngineSettings>? = null,
  internal val uploadNetwork: Flow<Boolean>? = null,
  internal val pauseUploads: (suspend (Boolean) -> Unit)? = null,
  private val remoteFactory: (RemoteConfig) -> RemoteInstance = ::remoteKetchInstance,
) {
  /** Whether an embedded instance is available. */
  val hasEmbedded: Boolean get() = embeddedFactory != null

  /** Whether this platform supports starting a local server. */
  val isLocalServerSupported: Boolean
    get() = localServerFactory != null

  private var localServer: LocalServerHandle? = null

  /** Devices that asked the local server for its access code, waiting for an answer. */
  val pairingRequests: PairingRequests = PairingRequests()

  /** Create the embedded [KetchApi] instance. */
  fun createEmbedded(): EmbeddedInstance {
    val ketch = embeddedFactory?.invoke()
      ?: throw UnsupportedOperationException("No embedded instance available")
    return EmbeddedInstance(
      instance = ketch,
      label = deviceName,
    )
  }

  /** Create a remote instance with a new, unconnected client from a [RemoteConfig]. */
  fun createRemote(config: RemoteConfig): RemoteInstance = remoteFactory(config)

  /**
   * Start a local HTTP server exposing [api], whose clients' download settings go to
   * [onConfigChanged]. Does not change the active instance.
   */
  fun startServer(api: KetchApi, onConfigChanged: suspend (DownloadConfig) -> Unit = {}) {
    val factory = localServerFactory
      ?: throw UnsupportedOperationException("Local server not supported on this platform")
    localServer = factory(LocalServerContext(api, pairingRequests, onConfigChanged))
  }

  /** Stop the local server if running (does not close Ketch). */
  fun stopServer() {
    localServer?.stop()
    localServer = null
  }
}

/**
 * What a local server serves, for [InstanceFactory]'s `localServerFactory`.
 *
 * @property api the embedded instance the server exposes.
 * @property pairingRequests where its pairing requests go.
 * @property onConfigChanged saves the download settings a client changed through the server, as
 *   the embedded device's Settings page saves its own; the server hands it every configuration
 *   `PUT /api/config` applied (`KetchServer`'s `onConfigChanged`), so they outlast a restart.
 */
class LocalServerContext(
  val api: KetchApi,
  val pairingRequests: PairingRequests,
  val onConfigChanged: suspend (DownloadConfig) -> Unit,
)

private fun remoteKetchInstance(config: RemoteConfig): RemoteInstance = RemoteInstance(
  client = RemoteKetch(config.host, config.port, config.apiToken, config.secure),
  remoteConfig = config,
)
