package com.linroid.kdown.api

import kotlinx.coroutines.flow.Flow

/**
 * A condition that must be met before a scheduled download can start.
 *
 * Conditions are provided by the consumer (e.g., WiFi-only, charging-only)
 * and are evaluated continuously. When all conditions for a task are met
 * simultaneously, the download proceeds.
 *
 * Example implementation:
 * ```kotlin
 * class WifiOnlyCondition(
 *   private val connectivityManager: ConnectivityManager
 * ) : DownloadCondition {
 *   override fun isMet(): Flow<Boolean> = connectivityManager.isWifiConnected
 * }
 * ```
 */
interface DownloadCondition {
  /** Emits `true` when the condition is satisfied, `false` when not. */
  fun isMet(): Flow<Boolean>
}
