package com.linroid.ketch.app.feedback

import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.deviceId
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.withTimeoutOrNull

/**
 * What clicking one of Ketch's notifications shows in the app.
 *
 * @property taskKey task to show in the inspector; `null` for a status tab.
 * @property filter status tab to show, such as Downloading for the ongoing notification.
 * @property retry whether Retry was clicked, which retries [taskKey].
 * @property deviceId device to show without a [taskKey]; `null` for this one.
 */
data class NotificationLink(
  val taskKey: TaskKey?,
  val filter: StatusFilter?,
  val retry: Boolean = false,
  val deviceId: String? = null,
) {
  companion object {
    /**
     * What clicking the notification about [event] shows: its task, or for a summary of several
     * finished downloads the Finished tab of their device.
     */
    fun of(event: ActivityEvent): NotificationLink = when (event) {
      is ActivityEvent.CompletedBatch, is ActivityEvent.QueueDrained -> NotificationLink(
        taskKey = null,
        filter = StatusFilter.Done,
        deviceId = ActivityRouting.deviceIdOf(event),
      )
      else -> NotificationLink(
        taskKey = ActivityRouting.taskKeyOf(event),
        filter = null,
        deviceId = ActivityRouting.deviceIdOf(event),
      )
    }

    /**
     * The file clicking the notification about [event] opens instead of showing its task: a
     * finished download's, when it was saved on this device.
     */
    fun filePathOf(event: ActivityEvent): String? =
      (event as? ActivityEvent.Completed)
        ?.takeIf { it.taskKey.deviceId == LOCAL_DEVICE_ID }
        ?.state?.outputPath

    // A click can start the app, or switch devices, before the device has loaded the task.
    internal val TASK_LOAD_TIMEOUT = 10.seconds
  }
}

/**
 * Shows what a notification click asks for: the device it is about, the status tab or the
 * task, which Retry also retries.
 */
suspend fun AppState.open(link: NotificationLink) {
  val key = link.taskKey
  val deviceId = key?.deviceId ?: link.deviceId ?: LOCAL_DEVICE_ID
  val entry = instances.value.firstOrNull { it.deviceId == deviceId } ?: return
  if (entry != activeInstance.value) switchInstance(entry)
  link.filter?.let { statusFilter = it }
  if (key == null) return
  val task = withTimeoutOrNull(NotificationLink.TASK_LOAD_TIMEOUT) {
    entry.instance.tasks.mapNotNull { tasks -> tasks.firstOrNull { it.taskId == key.taskId } }
      .first()
  } ?: return
  if (!statusFilter.matches(task.state.value)) statusFilter = StatusFilter.All
  inspect(key)
  if (link.retry) retry(task)
}
