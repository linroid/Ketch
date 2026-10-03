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
import com.linroid.ketch.config.TorrentSettings
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_call_failed
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Settings that belong to the instance doing the downloads rather than
 * to this app: download limits, network interfaces and torrent trackers.
 *
 * Changes apply immediately. For the embedded instance, download
 * settings are also saved to this app's config file; a remote instance
 * keeps them until it restarts, since the server does not persist them.
 * Torrent settings can only be changed on the embedded instance.
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
 * @param savedSpeedLimit the speed limit saved with download settings the
 *   instance accepted: its standing cap, which a slow lane holding the
 *   instance back for now must not replace.
 */
class InstanceSettingsController(
  private val api: KetchApi,
  private val local: AppSettingsController?,
  private val scope: CoroutineScope,
  private val applyTorrent: suspend (TorrentSettings) -> Unit = {},
  private val savedSpeedLimit: (DownloadConfig) -> SpeedLimit = { it.speedLimit },
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

  /**
   * Torrent settings of the embedded instance, or `null` for a remote
   * one, whose trackers are set in its own config file.
   */
  val torrent: TorrentSettings? get() = local?.config?.torrent

  /** Why the torrent settings could not be applied. */
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
  private var appliedTorrent: TorrentSettings? = null

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
   * Saves [settings] and applies them to the embedded instance, where
   * torrents pick them up as they start or resume. Does nothing for a
   * remote instance.
   */
  fun updateTorrent(settings: TorrentSettings) {
    val app = local ?: return
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
          val updated = api.updateNetworkInterfaces(NetworkInterfaceConfig(requested))
          confirmedNetworks = updated
          if (pendingNetworkIds == null) {
            networks = updated
            networkError = null
          }
        }
      }
    }
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
