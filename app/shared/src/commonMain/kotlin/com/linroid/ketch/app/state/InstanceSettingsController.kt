package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.instance.TrackerListStatus
import com.linroid.ketch.app.platform.localPeerDiscoverySupported
import com.linroid.ketch.config.TorrentSettings
import com.linroid.ketch.torrent.api.TorrentCapability
import com.linroid.ketch.torrent.api.TorrentController
import com.linroid.ketch.torrent.api.TorrentEngineSettings
import com.linroid.ketch.torrent.api.TorrentSettingsChange
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_call_failed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Settings that belong to the instance doing the downloads rather than
 * to this app: download limits, network interfaces and torrent settings.
 *
 * Changes apply immediately. For the embedded instance, download
 * settings are also saved to this app's config file, as are those other
 * devices change through its server ([acceptShared]). A remote instance
 * saves them itself when it lists `config.persisted`
 * (`KetchFeatures.CONFIG_PERSISTED`), and otherwise keeps them until it
 * restarts. Torrent settings are saved by the instance that runs them: this
 * app's config file for the embedded one, also when other devices change
 * them ([followEngineTorrent]), a remote device's own for a remote one whose
 * Ketch can change them over its API ([TorrentCapability.SETTINGS]).
 *
 * The download settings are read back from [KetchApi.status] after each
 * change, and whenever [loadDownload] is called, so they show what the
 * instance really uses, including a speed limit set from elsewhere.
 *
 * @param api instance the settings are read from and applied to.
 * @param local settings controller of this app when [api] is the
 *   embedded instance; `null` for a remote one.
 * @param scope scope that runs the calls to [api].
 * @param applyTorrent hands saved torrent settings to the embedded
 *   instance, so they take effect without a restart.
 * @param trackerList what each of the embedded instance's subscribed tracker lists holds.
 * @param refreshTrackerList downloads those lists again now.
 * @param savedSpeedLimit the speed limit saved with download settings the
 *   instance accepted: its standing cap, which a slow lane holding the
 *   instance back for now must not replace.
 * @param engineTorrent the torrent settings the embedded instance runs, which other devices may
 *   change through its server; [followEngineTorrent] saves those changes.
 */
class InstanceSettingsController(
  private val api: KetchApi,
  private val local: AppSettingsController?,
  private val scope: CoroutineScope,
  private val applyTorrent: suspend (TorrentSettings) -> Unit = {},
  trackerList: Flow<List<TrackerListStatus>>? = null,
  private val refreshTrackerList: () -> Unit = {},
  private val savedSpeedLimit: (DownloadConfig) -> SpeedLimit = { it.speedLimit },
  private val engineTorrent: StateFlow<TorrentEngineSettings>? = null,
) {
  /** Download settings, or `null` while a remote's are loading. */
  var download by mutableStateOf(local?.config?.download)
    private set

  /** Why the download settings could not be loaded or applied. */
  var downloadError by mutableStateOf<UiText?>(null)
    private set

  /** Network interfaces of the instance, or `null` while loading. */
  var networks by mutableStateOf<NetworkInterfaces?>(null)
    private set

  /** Why the network interfaces could not be loaded or changed. */
  var networkError by mutableStateOf<UiText?>(null)
    private set

  // A remote instance's torrent settings as the page shows them: what it last reported, or an
  // edit not confirmed yet.
  private var remoteTorrent by mutableStateOf<TorrentSettings?>(null)

  /**
   * Torrent settings of the instance, or `null` while a remote one's are loading or when it
   * cannot change them from here ([torrentUnsupported]). A remote instance's keep this app's
   * defaults for what only its host decides, the listen port and whether uploads wait for Wi-Fi.
   */
  val torrent: TorrentSettings? get() = if (local != null) local.config.torrent else remoteTorrent

  /**
   * Whether the torrent settings of a remote instance cannot be changed from here: its Ketch is
   * older, or it has no torrent support. `false` while they load, and for the embedded one.
   */
  var torrentUnsupported by mutableStateOf(false)
    private set

  /** Whether the instance can find peers on its local network, so its switch shows. */
  var localDiscoverySupported by mutableStateOf(local != null && localPeerDiscoverySupported)
    private set

  /**
   * What each subscribed tracker list holds, or `null` for a remote instance or one without
   * torrent support.
   */
  val trackerList: Flow<List<TrackerListStatus>>? = trackerList?.takeIf { local != null }

  /** Why the torrent settings could not be loaded or applied. */
  var torrentError by mutableStateOf<UiText?>(null)
    private set

  /** Whether the settings live on another device. */
  val isRemote: Boolean get() = local == null

  private val log = KetchLogger("InstanceSettings")
  private val downloadLock = Mutex()
  private val networkLock = Mutex()
  private val torrentLock = Mutex()
  private var appliedDownload: DownloadConfig? = null

  // Counts download edits; appliedEdit is the newest one sent to the instance, accepted or not. A
  // read-back only replaces what the page shows when no edit is waiting to be sent.
  private var edits = 0
  private var appliedEdit = 0

  // Counts the edits the instance accepted, which save themselves: settings applied from
  // elsewhere before one of them are not saved over it.
  private var acceptedEdits = 0
  private var appliedTorrent: TorrentSettings? = null

  // What a remote instance last confirmed of its torrent settings, and the edits made since that
  // are not sent yet, field by field. The page shows those edits over what it confirmed, so a
  // setting another device changed meanwhile is kept rather than sent back.
  private var confirmedTorrent: TorrentEngineSettings? = null
  private var pendingTorrent = TorrentSettingsChange()

  // What the instance last reported, to fall back to when a change fails.
  private var confirmedNetworks: NetworkInterfaces? = null

  // Newest selection not yet sent; older unsent ones are dropped.
  private var pendingNetworkIds: List<String>? = null

  /**
   * Reads the download settings the instance uses; call again to refresh them, such as after
   * switching to the instance or when the window regains focus. A failure is shown in
   * [downloadError] for a remote instance; the embedded one keeps showing its saved settings.
   */
  fun loadDownload() {
    scope.launch {
      if (local == null) downloadError = null
      downloadLock.withLock {
        attempt(onError = { if (local == null) downloadError = it }) { readDownload() }
      }
    }
  }

  /** Reads the network interfaces; call again to refresh them. */
  fun loadNetworks() {
    scope.launch {
      networkError = null
      networkLock.withLock {
        attempt(onError = {
          networkError = it
          if (networks == null) networks = NetworkInterfaces()
        }) {
          val loaded = api.networkInterfaces()
          confirmedNetworks = loaded
          if (pendingNetworkIds == null) networks = loaded
        }
      }
    }
  }

  /**
   * Applies [config] to the instance and, for the embedded one, saves it
   * once accepted, so a rejected value (e.g. a missing folder) is not
   * used again on the next launch.
   */
  fun updateDownload(config: DownloadConfig) {
    download = config
    edits++
    downloadError = null
    scope.launch {
      // Rapid changes queue up here; each turn applies the newest value,
      // so the instance always ends on what the page shows.
      downloadLock.withLock {
        val latest = download ?: return@withLock
        val edit = edits
        if (latest == appliedDownload) {
          appliedEdit = edit
          return@withLock
        }
        appliedEdit = edit
        attempt(onError = { downloadError = it }) {
          api.updateConfig(latest)
          acceptedEdits++
          appliedDownload = latest
          local?.saveDownload(latest.copy(speedLimit = savedSpeedLimit(latest)))
        }
        if (appliedDownload == latest) {
          attempt(onError = {}) { readDownload() }
        }
      }
    }
  }

  /**
   * Reads a remote instance's torrent settings; call again to refresh them. One whose Ketch
   * cannot change them from here sets [torrentUnsupported]. The embedded instance's are this
   * app's, so nothing is read for it.
   */
  fun loadTorrent() {
    if (local != null) return
    scope.launch {
      torrentLock.withLock {
        attempt(onError = { torrentError = it }) {
          readTorrent()
          torrentError = null
        }
      }
    }
  }

  /**
   * Takes [config], download settings a client of this device's server applied to the embedded
   * instance: saves them as [updateDownload] saves a change the instance accepted, and shows
   * them unless an edit made here waits to be sent, which then replaces them. Saves nothing once
   * the instance took an edit made here since, or for a remote instance.
   */
  suspend fun acceptShared(config: DownloadConfig) {
    val app = local ?: return
    val accepted = acceptedEdits
    downloadLock.withLock {
      // An edit made here that the instance took meanwhile replaced it, and was saved.
      if (acceptedEdits != accepted) return@withLock
      app.saveDownload(config.copy(speedLimit = savedSpeedLimit(config)))
      appliedDownload = config
      if (appliedEdit == edits) download = config
    }
  }

  /**
   * Applies [settings] to the instance: the upload mode and limit, encryption, uTP, port mapping
   * and local discovery reach running torrents at once, extra trackers and tracker lists reach
   * torrents as they start or resume. The embedded instance's are saved here first; a remote one
   * gets only the fields an edit changed, which it saves, so what another device changed
   * meanwhile stays, and a failure shows what it last confirmed again.
   */
  fun updateTorrent(settings: TorrentSettings) {
    val app = local ?: return updateRemoteTorrent(settings)
    app.saveTorrent(settings)
    torrentError = null
    scope.launch {
      // Like updateDownload: each turn applies the newest saved value.
      torrentLock.withLock {
        val latest = app.config.torrent
        if (latest == appliedTorrent) return@withLock
        attempt(onError = { torrentError = it }) {
          applyTorrent(latest)
          appliedTorrent = latest
        }
      }
    }
  }

  private fun updateRemoteTorrent(settings: TorrentSettings) {
    val shown = remoteTorrent ?: return
    // Only what this edit changed of what the page showed.
    val edit = TorrentSettingsChange.between(shown.engineSettings, settings.engineSettings)
    if (edit.isEmpty) return
    pendingTorrent = pendingTorrent.then(edit)
    remoteTorrent = settings
    torrentError = null
    scope.launch {
      // Each turn sends every edit made since the last, so the instance ends on all of them.
      torrentLock.withLock {
        val change = pendingTorrent
        if (change.isEmpty) return@withLock
        pendingTorrent = TorrentSettingsChange()
        attempt(onError = {
          torrentError = it
          // The edits sent are dropped; newer ones keep showing over what it last confirmed.
          confirmedTorrent?.let { confirmed ->
            remoteTorrent = pendingTorrent.applyTo(confirmed).asSettings()
          }
        }) {
          val controller = api.controller(TorrentController)
          if (controller == null) {
            // Its client changed to one without torrent controls since they were read.
            torrentUnsupported = true
            return@attempt
          }
          val updated = controller.updateSettings(change)
          confirmedTorrent = updated
          remoteTorrent = pendingTorrent.applyTo(updated).asSettings()
        }
      }
    }
  }

  /**
   * Saves the torrent settings other devices change on the embedded instance, through its
   * server, as [engineTorrent] reports them, so they show here and outlive a restart. Runs until
   * cancelled; does nothing for a remote instance.
   */
  suspend fun followEngineTorrent() {
    val app = local ?: return
    val engine = engineTorrent ?: return
    // The settings it started with are the ones saved.
    engine.drop(1).collect {
      torrentLock.withLock {
        // What it runs now, not what was collected: a change this page applied since shows.
        val running = engine.value
        val saved = app.config.torrent
        if (saved.engineSettings == running) return@withLock
        val adopted = saved.withEngineSettings(running)
        app.saveTorrent(adopted)
        appliedTorrent = adopted
      }
    }
  }

  /** Downloads the embedded instance's subscribed tracker lists again now. */
  fun refreshTrackerList() {
    if (local != null) refreshTrackerList.invoke()
  }

  /**
   * Routes new HTTP requests over the interfaces in [ids], in order;
   * an empty list restores the system's default routing.
   *
   * The selection shows at once. Selections made while one is being sent
   * are coalesced into the newest, and a failure falls back to what the
   * instance last confirmed.
   */
  fun selectNetworks(ids: List<String>) {
    val current = networks ?: return
    networks = current.copy(config = NetworkInterfaceConfig(ids))
    networkError = null
    pendingNetworkIds = ids
    scope.launch {
      networkLock.withLock {
        val requested = pendingNetworkIds ?: return@withLock
        pendingNetworkIds = null
        attempt(onError = {
          networkError = it
          // A newer selection is queued behind this one; let it decide.
          if (pendingNetworkIds == null) networks = confirmedNetworks ?: current
        }) {
          val updated = api.selectNetworkInterfaces(NetworkInterfaceConfig(requested))
          confirmedNetworks = updated
          if (pendingNetworkIds == null) {
            networks = updated
            networkError = null
          }
        }
      }
    }
  }

  /**
   * Reads a remote instance's torrent settings and what it can do with them, under
   * [torrentLock]; edits not sent yet keep showing over them.
   */
  private suspend fun readTorrent() {
    val controller = api.controller(TorrentController)
    val capabilities = controller?.capabilities()
    if (controller == null || capabilities?.supports(TorrentCapability.SETTINGS) != true) {
      torrentUnsupported = true
      return
    }
    val settings = controller.settings()
    torrentUnsupported = false
    localDiscoverySupported = capabilities.supports(TorrentCapability.LOCAL_DISCOVERY)
    confirmedTorrent = settings
    remoteTorrent = pendingTorrent.applyTo(settings).asSettings()
  }

  /** Shows what the instance reports, unless an edit made meanwhile has not been sent yet. */
  private suspend fun readDownload() {
    val before = edits
    val config = api.status().config
    if (edits == before && appliedEdit == edits) {
      download = config
      appliedDownload = config
    }
  }

  /** A remote instance's torrent settings as the page edits them. */
  private fun TorrentEngineSettings.asSettings(): TorrentSettings =
    TorrentSettings().withEngineSettings(this)

  /** This change, then [next]: the fields [next] sets replace this one's. */
  private fun TorrentSettingsChange.then(next: TorrentSettingsChange) = TorrentSettingsChange(
    upload = next.upload ?: upload,
    uploadLimit = next.uploadLimit ?: uploadLimit,
    encryption = next.encryption ?: encryption,
    utp = next.utp ?: utp,
    portMapping = next.portMapping ?: portMapping,
    localDiscovery = next.localDiscovery ?: localDiscovery,
    trackers = next.trackers ?: trackers,
    trackerList = next.trackerList ?: trackerList,
    trackerListUrls = next.trackerListUrls ?: trackerListUrls,
  )

  private suspend fun attempt(onError: (UiText) -> Unit, block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Instance settings call failed: ${e.describeCauses()}" }
      onError(e.message?.let(::verbatim) ?: Res.string.settings_call_failed.text())
    }
  }
}
