package com.linroid.ketch.app.ui.connect

import androidx.compose.runtime.Composable

/** Asks the system to let the app look for devices on the local network. */
internal fun interface NearbyAccess {
  /** Asks, where the platform needs it, then runs [onDone] whatever the answer. */
  fun request(onDone: () -> Unit)
}

/**
 * The [NearbyAccess] of this platform: Android asks for `NEARBY_WIFI_DEVICES` the first time
 * "Find on network" is tapped; elsewhere nothing is asked here.
 */
@Composable
internal expect fun rememberNearbyAccess(): NearbyAccess
