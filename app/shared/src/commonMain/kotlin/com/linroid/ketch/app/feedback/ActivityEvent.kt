package com.linroid.ketch.app.feedback

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.state.TaskKey

/**
 * Something that happened on a device and is worth telling the user about, as a toast while the
 * app is in front or a system notification otherwise.
 *
 * Task events carry the task's request and state, so consumers can name the file without
 * looking the task up.
 */
sealed interface ActivityEvent {
  /** A task was added. */
  data class Added(
    val taskKey: TaskKey,
    val request: DownloadRequest,
  ) : ActivityEvent

  /**
   * A torrent's file list arrived and it waits, paused, for the user to choose which files to
   * download.
   */
  data class FilesNeeded(
    val taskKey: TaskKey,
    val request: DownloadRequest,
  ) : ActivityEvent

  /** A task finished; [state] holds its output path, size and download time. */
  data class Completed(
    val taskKey: TaskKey,
    val request: DownloadRequest,
    val state: DownloadState.Completed,
  ) : ActivityEvent

  /** Several tasks finished close together and are reported as one. */
  data class CompletedBatch(
    val completions: List<Completed>,
  ) : ActivityEvent

  /** A task failed; [state] holds the error. */
  data class Failed(
    val taskKey: TaskKey,
    val request: DownloadRequest,
    val state: DownloadState.Failed,
  ) : ActivityEvent

  /** [count] tasks were queued or downloading when the device was first loaded. */
  data class Recovered(
    val deviceId: String,
    val count: Int,
  ) : ActivityEvent

  /**
   * Nothing is left to download on the device; [files] tasks totalling [bytes] finished since
   * it was last idle.
   */
  data class QueueDrained(
    val deviceId: String,
    val files: Int,
    val bytes: Long,
  ) : ActivityEvent

  /** The app lost its connection to the device. */
  data class DeviceOffline(val deviceId: String) : ActivityEvent

  /** The app reconnected to the device. */
  data class DeviceOnline(val deviceId: String) : ActivityEvent
}

/** A button on a system notification. Notifiers leave out the ones their platform lacks. */
enum class NotificationAction {
  /** Open the downloaded file. */
  Open,

  /** Show the downloaded file in the file manager. */
  Reveal,

  /** Share the downloaded file. */
  Share,

  /** Retry the failed task. */
  Retry,
}

/**
 * What a system notification says.
 *
 * @property title first line, such as "Download complete".
 * @property body second line, such as "ubuntu-24.04.iso · 5.7 GB in 3 min".
 * @property actions buttons to offer, in order.
 */
data class NotificationCopy(
  val title: String,
  val body: String,
  val actions: List<NotificationAction> = emptyList(),
)

/**
 * Posts system notifications, one implementation per platform.
 *
 * The host that owns the activity monitor calls it when the notification settings allow the
 * event. With `onlyInBackground` on, the default, that is only while the app is in the
 * background; in front, events show as toasts instead.
 */
fun interface SystemNotifier {
  /** Posts [copy] for [event]. */
  fun notify(event: ActivityEvent, copy: NotificationCopy)

  companion object {
    /** Posts nothing. */
    val None: SystemNotifier = SystemNotifier { _, _ -> }
  }
}
