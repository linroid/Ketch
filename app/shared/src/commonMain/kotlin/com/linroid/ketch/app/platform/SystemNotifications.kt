package com.linroid.ketch.app.platform

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf

/**
 * The system's side of the app's notifications, where Settings offers it: Android, whose download
 * service shows an ongoing notification and whose own settings tune each kind of notification.
 */
interface SystemNotifications {
  /**
   * Whether the system shows Live Updates, a running download as a chip in the status bar:
   * Android 16 and later.
   */
  val liveUpdates: Boolean

  /** Opens the system's notification settings for this app, which list its channels. */
  fun openSettings()
}

/**
 * [SystemNotifications] of the running app, `null` where Settings has nothing to offer about
 * them: every app but Android's.
 */
val LocalSystemNotifications: ProvidableCompositionLocal<SystemNotifications?> =
  staticCompositionLocalOf { null }
