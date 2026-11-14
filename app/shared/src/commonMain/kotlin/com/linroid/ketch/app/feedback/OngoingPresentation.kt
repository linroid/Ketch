package com.linroid.ketch.app.feedback

import com.linroid.ketch.config.NotificationSettings

/**
 * How the Android download service's ongoing notification shows, as `[notifications]` says.
 *
 * @property statusBarIcon whether its icon shows in the status bar: it is then posted in
 *   [CHANNEL_STATUS_BAR], which makes no sound but is not one of Android's silent channels,
 *   whose icons Android can hide from the status bar and some devices always do. Off posts it in
 *   [CHANNEL_SILENT].
 * @property liveUpdates whether a single download asks to be a Live Update, which Android 16
 *   and later show as a chip in the status bar.
 */
data class OngoingPresentation(
  val statusBarIcon: Boolean = true,
  val liveUpdates: Boolean = true,
) {
  /** Channel the ongoing notification is posted in. */
  val channel: String
    get() = if (statusBarIcon) CHANNEL_STATUS_BAR else CHANNEL_SILENT

  /**
   * The other channel, which is removed once nothing shows in it, so the system's settings list
   * one channel for running downloads. Android keeps what the user set for it should it return.
   */
  val unusedChannel: String
    get() = if (statusBarIcon) CHANNEL_SILENT else CHANNEL_STATUS_BAR

  /**
   * Whether the notification asks to be a Live Update, with a segment per connection: only for a
   * [singleDownload], as [OngoingDownloads.lanes] tells, on Android [sdk] 36 and later.
   */
  fun isLiveUpdate(singleDownload: Boolean, sdk: Int): Boolean =
    liveUpdates && singleDownload && sdk >= LIVE_UPDATES_SDK

  companion object {
    /** Channel of low importance, which Android lists among the silent notifications. */
    const val CHANNEL_SILENT: String = "downloads_active"

    /** Channel of default importance without sound or vibration. */
    const val CHANNEL_STATUS_BAR: String = "downloads_progress"

    /** First Android version with Live Updates: Android 16. */
    const val LIVE_UPDATES_SDK: Int = 36

    /** What [settings] ask for. */
    fun of(settings: NotificationSettings): OngoingPresentation = OngoingPresentation(
      statusBarIcon = settings.statusBarIcon,
      liveUpdates = settings.liveUpdates,
    )
  }
}
