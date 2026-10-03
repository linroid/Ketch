@file:OptIn(ExperimentalWasmJsInterop::class)

package com.linroid.ketch.app.feedback

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.percentText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AppController
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.notify_web_offer
import ketch.app.shared.generated.resources.notify_web_on
import ketch.app.shared.generated.resources.pulse_downloading_count
import ketch.app.shared.generated.resources.pulse_tab_progress
import kotlinx.browser.document
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.transform
import kotlinx.coroutines.launch
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.time.Duration.Companion.seconds

/**
 * Posts Ketch's notifications in the browser with the Notification API, and shows the number of
 * failed downloads on the installed web app's icon.
 *
 * Browsers only grant the permission from a user action, so [requestPermission] is called from
 * a button, such as the first completion toast's "Notify me", never on load.
 *
 * @param onClick runs when a notification is clicked, after the page took focus.
 */
class WebNotifier(private val onClick: (ActivityEvent) -> Unit = {}) : SystemNotifier {
  private val log = KetchLogger("WebNotifier")

  /** Whether the user allowed notifications. */
  val isAllowed: Boolean get() = notificationPermission() == "granted"

  /** Whether asking would show the browser's prompt, because the user has not decided yet. */
  val canAsk: Boolean get() = notificationPermission() == "default"

  /**
   * Asks the user to allow notifications; call it from a user action, such as a click.
   *
   * @param onResult receives whether the user allowed them.
   */
  fun requestPermission(onResult: (granted: Boolean) -> Unit = {}) {
    requestNotificationPermission { onResult(it == "granted") }
  }

  override fun notify(event: ActivityEvent, copy: NotificationCopy) {
    if (!isAllowed) return
    // A task's newer notification replaces its older one, and a summary the previous summary.
    val tag = ActivityRouting.taskKeyOf(event)?.encode()
      ?: "device:${ActivityRouting.deviceIdOf(event)}"
    val error = showNotification(copy.title, copy.body, tag) { onClick(event) }
    if (error != null) log.d { "Could not show a notification: $error" }
  }

  /** Shows [count] on the installed web app's icon; `0` clears it. */
  fun setBadge(count: Int) {
    setAppBadge(count)
  }
}

/**
 * Reports what happens on the device the web app shows, as the notification settings allow:
 * events go to the app while the page has focus and become notifications otherwise. The tab
 * title shows the progress of its downloads and the installed app's icon its failures, and the
 * first completion offers to turn notifications on. Runs until the controller closes.
 *
 * @return the events for `App(activityEvents = …)`.
 */
fun reportWebActivity(controller: AppController): Flow<ActivityEvent> {
  val state = controller.state
  val manager = controller.instanceManager
  val notifier = WebNotifier { event -> ActivityRouting.taskKeyOf(event)?.let(state::inspect) }
  val devices = ActivityRouting.devices(manager)
  val monitor = ActivityMonitor(devices, controller.scope)
  val toasts = Channel<ActivityEvent>(Channel.UNLIMITED)
  var offered = false
  controller.scope.launch {
    monitor.events.collect { event ->
      val settings = controller.appSettings.config.notifications
      val delivery = ActivityRouting.deliveryOf(event, settings, inFront = document.hasFocus())
      val copy = ActivityRouting.copyOf(event, ActivityRouting.deviceNameOf(event, manager))
      // Offered on a finished download that would be a notification in the background.
      val offer = copy?.takeIf {
        !offered && event.isFinish && notifier.canAsk &&
          ActivityRouting.deliveryOf(event, settings, inFront = false).notify
      }
      when {
        !delivery.toast -> {}
        offer != null -> {
          offered = true
          offerNotifications(state.messages, notifier, event, offer)
        }
        else -> toasts.send(event)
      }
      if (delivery.notify && copy != null) notifier.notify(event, copy)
    }
  }
  controller.scope.launch { ActivityRouting.failures(devices).collect { notifier.setBadge(it) } }
  controller.scope.launch { showProgressInTitle(manager) }
  return toasts.receiveAsFlow()
}

private val ActivityEvent.isFinish: Boolean
  get() = this is ActivityEvent.Completed || this is ActivityEvent.CompletedBatch ||
    this is ActivityEvent.QueueDrained

// The toast the app would show for the event, with a button that asks to notify next time.
private fun offerNotifications(
  messages: MessageCenter,
  notifier: WebNotifier,
  event: ActivityEvent,
  copy: NotificationCopy,
) {
  messages.post(
    level = MessageLevel.Success,
    title = verbatim(copy.title),
    detail = copy.body.ifEmpty { null }?.let(::verbatim),
    taskKey = ActivityRouting.taskKeyOf(event),
    deviceId = ActivityRouting.deviceIdOf(event),
    actions = listOf(
      MessageAction(Res.string.notify_web_offer.text()) {
        notifier.requestPermission { granted ->
          if (granted) messages.post(MessageLevel.Success, Res.string.notify_web_on.text())
        }
      }
    ),
  )
}

// "↓ 45% · Ketch" while the shown device downloads; the page's own title otherwise.
@OptIn(ExperimentalCoroutinesApi::class)
private suspend fun showProgressInTitle(manager: InstanceManager) {
  val idleTitle = document.title
  manager.activeApi
    .flatMapLatest { it.tasks }
    .flatMapLatest { tasks ->
      if (tasks.isEmpty()) flowOf(emptyList()) else combine(tasks.map { it.state }) { it.toList() }
    }
    .conflate()
    .transform {
      emit(it)
      delay(TITLE_INTERVAL)
    }
    .map(::progressTitle)
    .distinctUntilChanged()
    .collect { progress ->
      document.title = progress?.let { listOf(it, verbatim(APP_NAME)).joinText().load() }
        ?: idleTitle
    }
}

// Progress of the downloading tasks of known size, or their count when no size is known.
private fun progressTitle(states: List<DownloadState>): UiText? {
  val downloading = states.filterIsInstance<DownloadState.Downloading>()
  if (downloading.isEmpty()) return null
  val sized = downloading.map { it.progress }.filter { it.totalBytes > 0 }
  val progress = if (sized.isEmpty()) {
    Res.string.pulse_downloading_count.text(downloading.size)
  } else {
    percentText((sized.sumOf { it.downloadedBytes } * 100 / sized.sumOf { it.totalBytes }).toInt())
  }
  return Res.string.pulse_tab_progress.text(progress)
}

private const val APP_NAME = "Ketch"

private val TITLE_INTERVAL = 1.seconds

private fun notificationPermission(): String =
  js("('Notification' in window) ? Notification.permission : 'unsupported'")

private fun requestNotificationPermission(onResult: (String) -> Unit): Unit = js(
  """{
  if (!('Notification' in window)) {
    onResult('unsupported');
    return;
  }
  Notification.requestPermission().then((p) => onResult(p), () => onResult('denied'));
}"""
)

// Returns why the notification could not be shown, or null. Chrome on Android only shows
// notifications from a service worker, so the constructor throws there.
private fun showNotification(
  title: String,
  body: String,
  tag: String,
  onClick: () -> Unit,
): String? = js(
  """{
  try {
    const n = new Notification(title, { body: body, tag: tag, icon: 'icon-192.png' });
    n.onclick = () => {
      window.focus();
      n.close();
      onClick();
    };
    return null;
  } catch (e) {
    return String((e && e.message) || e);
  }
}"""
)

// Installed web apps show the badge on their icon; elsewhere the call does nothing.
private fun setAppBadge(count: Int): Unit = js(
  """{
  if (!('setAppBadge' in navigator)) return;
  const done = count > 0 ? navigator.setAppBadge(count) : navigator.clearAppBadge();
  done.catch(() => {});
}"""
)
