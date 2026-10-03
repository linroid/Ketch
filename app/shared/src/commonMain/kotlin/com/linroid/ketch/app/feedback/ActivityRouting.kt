package com.linroid.ketch.app.feedback

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.app.util.toCopy
import com.linroid.ketch.app.util.transferSummary
import com.linroid.ketch.config.NotificationMode
import com.linroid.ketch.config.NotificationSettings
import com.linroid.ketch.remote.ConnectionState
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.notify_all_finished
import ketch.app.shared.generated.resources.notify_download_complete
import ketch.app.shared.generated.resources.notify_download_failed
import ketch.app.shared.generated.resources.notify_downloads_finished
import ketch.app.shared.generated.resources.notify_failed_body
import ketch.app.shared.generated.resources.notify_on_device
import ketch.app.shared.generated.resources.row_files
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map

/**
 * How the hosts that own an [ActivityMonitor] report its events: as an in-app toast, a system
 * notification or not at all under the user's [NotificationSettings], and what the notification
 * says. The hosts share it so every platform reports alike.
 */
object ActivityRouting {
  /**
   * Where a host reports an event.
   *
   * @property toast whether it goes to the app, which shows it as a toast.
   * @property notify whether it is posted as a system notification.
   */
  data class Delivery(val toast: Boolean, val notify: Boolean)

  /**
   * Where to report [event] under [settings]. Toasts only show while the app is [inFront];
   * notifications are posted while it is in the background, and also in front when
   * [NotificationSettings.onlyInBackground] is off. Events about adding, recovering and
   * connecting are only ever toasts.
   */
  fun deliveryOf(
    event: ActivityEvent,
    settings: NotificationSettings,
    inFront: Boolean,
  ): Delivery {
    val muted = deviceIdOf(event)?.let { it in settings.mutedDevices } == true
    val mode = if (muted) NotificationMode.Off else modeOf(event, settings)
    return when (mode) {
      NotificationMode.Off -> Delivery(toast = false, notify = false)
      NotificationMode.InApp -> Delivery(toast = inFront, notify = false)
      NotificationMode.Notify -> Delivery(
        toast = inFront,
        notify = !inFront || !settings.onlyInBackground,
      )
    }
  }

  /**
   * What the notification for [event] says, in the language of the app's window, or `null` for
   * events that are never notified. Every action a platform might offer is listed; notifiers
   * leave out the ones they lack.
   *
   * @param deviceName device to name in the title, such as "NAS-Basement" for "On
   *   NAS-Basement: Download complete"; `null` when the event is about the device the app shows.
   */
  suspend fun copyOf(event: ActivityEvent, deviceName: String? = null): NotificationCopy? {
    suspend fun on(title: UiText): String = if (deviceName == null) {
      title.load()
    } else {
      Res.string.notify_on_device.text(deviceName, title).load()
    }
    return when (event) {
      is ActivityEvent.Completed -> NotificationCopy(
        title = on(Res.string.notify_download_complete.text()),
        body = (listOf(verbatim(displayName(event.request, event.state))) +
          transferSummary(event.state)).joinText().load(),
        actions = COMPLETED_ACTIONS,
      )
      is ActivityEvent.CompletedBatch -> {
        val bytes = event.completions.sumOf { it.state.totalBytes ?: 0L }
        NotificationCopy(
          title = on(Res.plurals.notify_downloads_finished.text(event.completions.size)),
          body = if (bytes > 0) sizeText(bytes).load() else "",
        )
      }
      is ActivityEvent.Failed -> NotificationCopy(
        title = on(Res.string.notify_download_failed.text()),
        body = Res.string.notify_failed_body.text(
          displayName(event.request, event.state),
          event.state.error.toCopy().title,
        ).load(),
        actions = listOf(NotificationAction.Retry),
      )
      is ActivityEvent.QueueDrained -> NotificationCopy(
        title = on(Res.string.notify_all_finished.text()),
        body = listOfNotNull(
          Res.plurals.row_files.text(event.files),
          sizeText(event.bytes).takeIf { event.bytes > 0 },
        ).joinText().load(),
      )
      is ActivityEvent.Added,
      is ActivityEvent.Recovered,
      is ActivityEvent.DeviceOffline,
      is ActivityEvent.DeviceOnline -> null
    }
  }

  /**
   * Name of the device [event] happened on when [manager] shows another one, for [copyOf];
   * `null` when it is the device the app shows.
   */
  fun deviceNameOf(event: ActivityEvent, manager: InstanceManager): String? {
    val deviceId = deviceIdOf(event) ?: return null
    if (deviceId == (manager.activeInstance.value?.deviceId ?: LOCAL_DEVICE_ID)) return null
    return manager.instances.value.firstOrNull { it.deviceId == deviceId }?.label ?: deviceId
  }

  /** Id of the device [event] happened on. */
  fun deviceIdOf(event: ActivityEvent): String? = when (event) {
    is ActivityEvent.Added -> event.taskKey.deviceId
    is ActivityEvent.Completed -> event.taskKey.deviceId
    is ActivityEvent.CompletedBatch -> event.completions.firstOrNull()?.taskKey?.deviceId
    is ActivityEvent.Failed -> event.taskKey.deviceId
    is ActivityEvent.Recovered -> event.deviceId
    is ActivityEvent.QueueDrained -> event.deviceId
    is ActivityEvent.DeviceOffline -> event.deviceId
    is ActivityEvent.DeviceOnline -> event.deviceId
  }

  /** Task [event] is about, or `null` when it is about a device or several tasks. */
  fun taskKeyOf(event: ActivityEvent): TaskKey? = when (event) {
    is ActivityEvent.Added -> event.taskKey
    is ActivityEvent.Completed -> event.taskKey
    is ActivityEvent.Failed -> event.taskKey
    else -> null
  }

  /**
   * The devices an [ActivityMonitor] of [manager]'s app watches: the embedded one and the remote
   * the app is connected to, which is the active one.
   */
  fun devices(manager: InstanceManager): Flow<List<ActivitySource>> =
    combine(manager.instances, manager.activeInstance) { instances, active ->
      instances.filter { it is EmbeddedInstance || (it is RemoteInstance && it == active) }
    }.distinctUntilChanged().map { entries -> entries.map(::sourceOf) }

  /** Number of failed tasks, not counting canceled ones, on the [devices]. */
  @OptIn(ExperimentalCoroutinesApi::class)
  fun failures(devices: Flow<List<ActivitySource>>): Flow<Int> =
    devices.flatMapLatest { sources ->
      if (sources.isEmpty()) {
        flowOf(0)
      } else {
        combine(sources.map { it.tasks.flatMapLatest(::failedCount) }) { it.sum() }
      }
    }.distinctUntilChanged()

  private fun modeOf(event: ActivityEvent, settings: NotificationSettings): NotificationMode =
    when (event) {
      is ActivityEvent.Completed, is ActivityEvent.CompletedBatch -> settings.finished
      is ActivityEvent.Failed -> settings.failed
      is ActivityEvent.QueueDrained ->
        if (settings.queueDrained) settings.finished else NotificationMode.Off
      is ActivityEvent.DeviceOffline, is ActivityEvent.DeviceOnline ->
        if (settings.deviceOffline) NotificationMode.InApp else NotificationMode.Off
      is ActivityEvent.Added, is ActivityEvent.Recovered -> NotificationMode.InApp
    }

  private fun sourceOf(entry: InstanceEntry): ActivitySource = ActivitySource(
    deviceId = entry.deviceId,
    tasks = entry.instance.tasks,
    online = when (entry) {
      is RemoteInstance -> entry.connectionState.map { it == ConnectionState.Connected }
      else -> flowOf(true)
    },
  )

  @OptIn(ExperimentalCoroutinesApi::class)
  private fun failedCount(tasks: List<DownloadTask>): Flow<Int> {
    if (tasks.isEmpty()) return flowOf(0)
    val failed = tasks.map { task ->
      task.state.map { it is DownloadState.Failed }.distinctUntilChanged()
    }
    return combine(failed) { flags -> flags.count { it } }
  }

  private val COMPLETED_ACTIONS =
    listOf(NotificationAction.Open, NotificationAction.Reveal, NotificationAction.Share)
}
