package com.linroid.ketch.app.ui.connect

import androidx.compose.runtime.Composable

/** Asks the system to let the app reach devices on the local network. */
internal fun interface NearbyAccess {
  /**
   * Asks, where the platform needs it, then runs [onDone] whatever the answer. [search] is
   * whether the app looks for devices (mDNS) rather than connects to one it was given.
   */
  fun request(search: Boolean, onDone: () -> Unit)
}

/**
 * The [NearbyAccess] of this platform: Android 17 asks for `ACCESS_LOCAL_NETWORK` before
 * searching or connecting, and 13 to 16 for `NEARBY_WIFI_DEVICES` the first time "Find on
 * network" is tapped; elsewhere nothing is asked here.
 */
@Composable
internal expect fun rememberNearbyAccess(): NearbyAccess
