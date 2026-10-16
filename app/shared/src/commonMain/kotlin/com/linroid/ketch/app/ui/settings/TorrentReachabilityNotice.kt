package com.linroid.ketch.app.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.torrent.api.TorrentCapability
import com.linroid.ketch.torrent.api.TorrentReachability
import com.linroid.ketch.torrent.api.TorrentReachabilityStatus
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_torrent_reach_blocked
import ketch.app.shared.generated.resources.settings_torrent_reach_forwarded
import ketch.app.shared.generated.resources.settings_torrent_reach_hole_punching
import ketch.app.shared.generated.resources.settings_torrent_reach_no_hole_punching
import ketch.app.shared.generated.resources.settings_torrent_reach_not_forwarded
import ketch.app.shared.generated.resources.settings_torrent_reach_reachable
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.seconds

/**
 * Whether peers can connect to [device]'s torrent engine, asked every 10 s while it shows: for
 * the embedded device and remote ones alike, and nothing for devices that cannot say.
 */
@Composable
internal fun TorrentReachabilityNotice(state: AppState, device: InstanceEntry) {
  // A reconnect replaces a remote device's entry and client, and its features arrive later:
  // follow both, so polling moves to the live client.
  val entries by state.instances.collectAsState()
  val presence by state.instanceManager.presence.collectAsState()
  val entry = entries.firstOrNull { it.deviceId == device.deviceId }
  val features = presence.firstOrNull { it.deviceId == device.deviceId }?.status?.features
  val controller = remember(entry, features) { state.torrentControllerOf(device.deviceId) }
    ?: return
  val reachability by produceState<TorrentReachability?>(null, controller) {
    // Older devices do not list it; asking them anyway would only fail.
    val supported = try {
      controller.capabilities().supports(TorrentCapability.REACHABILITY)
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      false
    }
    while (supported) {
      value = try {
        controller.reachability()
      } catch (e: CancellationException) {
        throw e
      } catch (_: Exception) {
        null
      }
      delay(REFRESH)
    }
  }
  val notice = reachability?.let { reachabilityNotice(it, device.label) } ?: return
  SettingsNotice(text = notice.first.resolve(), tone = notice.second)
}

/**
 * What the notice says about [reachability] of the device named [device], and its tone; `null`
 * while it is unknown. A device peers cannot reach says whether hole punching still reaches peers
 * behind routers.
 */
internal fun reachabilityNotice(
  reachability: TorrentReachability,
  device: String,
): Pair<UiText, NoticeTone>? {
  val port = reachability.port ?: return null
  val punching = if (reachability.holePunching) {
    Res.string.settings_torrent_reach_hole_punching.text()
  } else {
    Res.string.settings_torrent_reach_no_hole_punching.text()
  }
  return when (reachability.status) {
    TorrentReachabilityStatus.UNKNOWN -> null
    TorrentReachabilityStatus.REACHABLE ->
      Res.string.settings_torrent_reach_reachable.text(device, port) to NoticeTone.Success
    TorrentReachabilityStatus.FORWARDED -> Res.plurals.settings_torrent_reach_forwarded
      .text(reachability.forwardedLayers, device, port, reachability.forwardedLayers) to
      NoticeTone.Success
    TorrentReachabilityStatus.BLOCKED ->
      listOf(Res.string.settings_torrent_reach_blocked.text(device, port), punching)
        .joinText(" ") to NoticeTone.Warning
    TorrentReachabilityStatus.NOT_FORWARDED ->
      listOf(Res.string.settings_torrent_reach_not_forwarded.text(device, port), punching)
        .joinText(" ") to NoticeTone.Info
  }
}

private val REFRESH = 10.seconds
