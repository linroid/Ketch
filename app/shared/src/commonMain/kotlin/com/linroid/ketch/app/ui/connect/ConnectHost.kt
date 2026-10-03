package com.linroid.ketch.app.ui.connect

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DiscoveryState
import com.linroid.ketch.app.util.PairingLink
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.connect_link_incomplete
import ketch.app.shared.generated.resources.connect_link_incomplete_detail

private val log = KetchLogger("ConnectHost")

/**
 * Connecting other devices, over whatever the shell shows: the confirmation of each pairing link
 * opened from outside the app ([com.linroid.ketch.app.state.IncomingDownloads.pendingPairings]),
 * such as a code scanned with the camera, one at a time; then the [AddDeviceSheet] while
 * [AppState.showAddRemoteDialog] asks for it, for [AppState.unauthorizedInstance] when that is
 * set. A search of the network started with it ([AppState.discoverRemoteServers]) runs in the
 * sheet instead.
 *
 * While no device is shown the shell shows [ConnectLanding], which takes the sheet's place.
 */
@Composable
fun ConnectHost(state: AppState) {
  val pairings by state.incoming.pendingPairings.collectAsState()
  val active by state.activeInstance.collectAsState()
  val pairing = pairings.firstOrNull()
  if (pairing != null) {
    val link = remember(pairing) { PairingLink.parse(pairing.link) }
    if (link == null) {
      LaunchedEffect(pairing) {
        log.w { "Ignoring a pairing link that names no device: ${redactUrl(pairing.label)}" }
        state.messages.post(
          level = MessageLevel.Error,
          title = Res.string.connect_link_incomplete.text(),
          detail = Res.string.connect_link_incomplete_detail.text(),
        )
        state.incoming.complete(pairing)
      }
    } else {
      PairingSheet(state, pairing, link, onDone = { state.incoming.complete(pairing) })
    }
    return
  }
  val asked = state.showAddRemoteDialog
  if (active == null) {
    // The landing page has the same fields, so nothing opens over it.
    LaunchedEffect(asked) { if (asked) state.showAddRemoteDialog = false }
    return
  }
  if (asked) {
    // "Find on network" elsewhere starts the app's own search; the sheet's search takes over.
    val searchNow = remember { state.discoveryState is DiscoveryState.Discovering }
    LaunchedEffect(Unit) { if (searchNow) state.resetDiscovery() }
    AddDeviceSheet(
      state = state,
      device = state.unauthorizedInstance,
      searchNow = searchNow,
      onDismiss = {
        state.showAddRemoteDialog = false
        state.unauthorizedInstance = null
      },
    )
  }
}
