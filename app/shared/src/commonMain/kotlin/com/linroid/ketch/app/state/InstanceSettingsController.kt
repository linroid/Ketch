package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaces
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Settings that belong to the instance doing the downloads rather than
 * to this app: download limits and network interfaces.
 *
 * Changes apply immediately. For the embedded instance, download
 * settings are also saved to this app's config file; a remote instance
 * keeps them until it restarts, since the server does not persist them.
 *
 * @param api instance the settings are read from and applied to.
 * @param local settings controller of this app when [api] is the
 *   embedded instance; `null` for a remote one.
 * @param scope scope that runs the calls to [api].
 */
class InstanceSettingsController(
  private val api: KetchApi,
  private val local: AppSettingsController?,
  private val scope: CoroutineScope,
) {
  /** Download settings, or `null` while a remote's are loading. */
  var download by mutableStateOf(local?.config?.download)
    private set

  /** Why the download settings could not be loaded or applied. */
  var downloadError by mutableStateOf<String?>(null)
    private set

  /** Network interfaces of the instance, or `null` while loading. */
  var networks by mutableStateOf<NetworkInterfaces?>(null)
    private set

  /** Why the network interfaces could not be loaded or changed. */
  var networkError by mutableStateOf<String?>(null)
    private set

  /** Whether the settings live on another device. */
  val isRemote: Boolean get() = local == null

  private val downloadLock = Mutex()
  private val networkLock = Mutex()
  private var appliedDownload: DownloadConfig? = null

  /** Reads the download settings of a remote instance. */
  fun loadDownload() {
    if (local != null) return
    scope.launch {
      downloadError = null
      attempt(onError = { downloadError = it }) {
        val config = api.status().config
        appliedDownload = config
        download = config
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
          networks = api.networkInterfaces()
        }
      }
    }
  }

  /** Saves [config] (embedded only) and applies it to the instance. */
  fun updateDownload(config: DownloadConfig) {
    download = config
    downloadError = null
    local?.saveDownload(config)
    scope.launch {
      // Rapid changes queue up here; each turn applies the newest value,
      // so the instance always ends on what the page shows.
      downloadLock.withLock {
        val latest = download ?: return@withLock
        if (latest == appliedDownload) return@withLock
        attempt(onError = { downloadError = it }) {
          api.updateConfig(latest)
          appliedDownload = latest
        }
      }
    }
  }

  /**
   * Routes new HTTP requests over the interfaces in [ids], in order;
   * an empty list restores the system's default routing.
   */
  fun selectNetworks(ids: List<String>) {
    val current = networks ?: return
    networks = current.copy(config = NetworkInterfaceConfig(ids))
    networkError = null
    scope.launch {
      networkLock.withLock {
        attempt(onError = {
          networkError = it
          networks = current
        }) {
          networks = api.updateNetworkInterfaces(NetworkInterfaceConfig(ids))
        }
      }
    }
  }

  private suspend fun attempt(onError: (String) -> Unit, block: suspend () -> Unit) {
    try {
      block()
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      onError(e.message ?: "Something went wrong. Try again.")
    }
  }
}
