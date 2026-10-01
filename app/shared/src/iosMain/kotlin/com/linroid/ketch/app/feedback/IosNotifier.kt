package com.linroid.ketch.app.feedback

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.app.platform.FileActionException
import com.linroid.ketch.app.platform.FileActions
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.deviceId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSError
import platform.UIKit.UIApplication
import platform.UIKit.UIApplicationState
import platform.UserNotifications.UNAuthorizationOptionAlert
import platform.UserNotifications.UNAuthorizationOptionBadge
import platform.UserNotifications.UNMutableNotificationContent
import platform.UserNotifications.UNNotification
import platform.UserNotifications.UNNotificationAction
import platform.UserNotifications.UNNotificationActionOptionForeground
import platform.UserNotifications.UNNotificationCategory
import platform.UserNotifications.UNNotificationCategoryOptionNone
import platform.UserNotifications.UNNotificationPresentationOptionBanner
import platform.UserNotifications.UNNotificationPresentationOptionList
import platform.UserNotifications.UNNotificationPresentationOptions
import platform.UserNotifications.UNNotificationRequest
import platform.UserNotifications.UNNotificationResponse
import platform.UserNotifications.UNNotificationTrigger
import platform.UserNotifications.UNTimeIntervalNotificationTrigger
import platform.UserNotifications.UNUserNotificationCenter
import platform.UserNotifications.UNUserNotificationCenterDelegateProtocol
import platform.darwin.NSObject
import platform.darwin.dispatch_async
import platform.darwin.dispatch_get_main_queue

/**
 * Posts Ketch's notifications on iOS with `UNUserNotificationCenter`, and shows the number of
 * failed downloads on the app icon.
 *
 * Permission is asked for after the first download is added, never at launch. Tapping a
 * notification opens Ketch on its task; a finished download on this device offers Open and
 * Share, and a failure Retry. The Swift app calls [install] before it finishes launching, so a
 * tap that launches Ketch is not lost.
 */
object IosNotifier : SystemNotifier {
  private val log = KetchLogger("IosNotifier")
  private val delegate = Delegate()
  private val taps = Channel<NotificationTap>(Channel.UNLIMITED)
  private var installed = false
  private var authorizationRequested = false

  // Unknown until the first count, so a badge left from an earlier launch is replaced.
  private var badge: Int? = null

  private val center: UNUserNotificationCenter
    get() = UNUserNotificationCenter.currentNotificationCenter()

  /** Taps on Ketch's notifications, oldest first; each goes to one collector. */
  internal val tapped: Flow<NotificationTap> = taps.receiveAsFlow()

  /** Starts handling taps on Ketch's notifications. Call it before the app finishes launching. */
  fun install() {
    if (installed) return
    installed = true
    center.delegate = delegate
    center.setNotificationCategories(
      setOf(
        category(CATEGORY_COMPLETED, NotificationAction.Open, NotificationAction.Share),
        category(CATEGORY_FAILED, NotificationAction.Retry),
      ),
    )
  }

  /** Asks to notify and badge once per launch; iOS only prompts the first time. */
  internal fun requestAuthorization() {
    if (authorizationRequested) return
    authorizationRequested = true
    val options = UNAuthorizationOptionAlert or UNAuthorizationOptionBadge
    center.requestAuthorizationWithOptions(options) { granted, error ->
      if (error != null) log.w { "Could not ask to notify: ${error.describe()}" }
      log.i { "Notifications ${if (granted) "allowed" else "not allowed"}" }
      // The badge set before permission was granted did not show.
      if (granted) dispatch_async(dispatch_get_main_queue()) { applyBadge() }
    }
  }

  override fun notify(event: ActivityEvent, copy: NotificationCopy) {
    val content = UNMutableNotificationContent()
    content.setTitle(copy.title)
    content.setBody(copy.body)
    ActivityRouting.deviceIdOf(event)?.let { content.setThreadIdentifier(it) }
    val key = ActivityRouting.taskKeyOf(event)
    // Only files on this device can be opened from here.
    val path = (event as? ActivityEvent.Completed)?.state?.outputPath
      ?.takeIf { key?.deviceId == LOCAL_DEVICE_ID }
    content.setUserInfo(
      listOfNotNull(
        key?.let { USER_INFO_TASK to it.encode() },
        path?.let { USER_INFO_PATH to it },
      ).toMap<Any?, Any?>(),
    )
    when {
      path != null && NotificationAction.Open in copy.actions ->
        content.setCategoryIdentifier(CATEGORY_COMPLETED)
      NotificationAction.Retry in copy.actions -> content.setCategoryIdentifier(CATEGORY_FAILED)
    }
    // A task's newer notification replaces its older one, and a summary the previous summary.
    val id = key?.let { "task:${it.encode()}" } ?: "device:${ActivityRouting.deviceIdOf(event)}"
    post(id, content)
  }

  /** Shows [count] failed downloads on the app icon; `0` clears it. */
  internal fun setBadge(count: Int) {
    if (count == badge) return
    badge = count
    applyBadge()
  }

  /**
   * Tells the user that [count] downloads were paused for the background. The notice shows
   * after a minute, so a quick trip to another app never posts it.
   */
  internal fun notifyPaused(count: Int) {
    val content = UNMutableNotificationContent()
    content.setTitle(if (count == 1) "1 download paused" else "$count downloads paused")
    content.setBody("Open Ketch to continue")
    val trigger = UNTimeIntervalNotificationTrigger.triggerWithTimeInterval(
      timeInterval = PAUSED_NOTICE_DELAY_SECONDS,
      repeats = false,
    )
    post(PAUSED_ID, content, trigger)
  }

  /** Withdraws the notice of [notifyPaused], posted or not. */
  internal fun clearPaused() {
    center.removePendingNotificationRequestsWithIdentifiers(listOf(PAUSED_ID))
    center.removeDeliveredNotificationsWithIdentifiers(listOf(PAUSED_ID))
  }

  private fun post(
    id: String,
    content: UNMutableNotificationContent,
    trigger: UNNotificationTrigger? = null,
  ) {
    val request = UNNotificationRequest.requestWithIdentifier(id, content, trigger)
    center.addNotificationRequest(request) { error ->
      // Fails while the user has not allowed notifications.
      if (error != null) log.d { "Could not post a notification: ${error.describe()}" }
    }
  }

  private fun applyBadge() {
    val count = badge ?: return
    center.setBadgeCount(count.toLong()) { error ->
      if (error != null) log.d { "Could not set the badge: ${error.describe()}" }
    }
  }

  private fun category(id: String, vararg actions: NotificationAction): UNNotificationCategory =
    UNNotificationCategory.categoryWithIdentifier(
      identifier = id,
      actions = actions.map { action ->
        UNNotificationAction.actionWithIdentifier(
          identifier = action.name,
          title = when (action) {
            NotificationAction.Open -> "Open"
            NotificationAction.Reveal -> "Show in Files"
            NotificationAction.Share -> "Share"
            NotificationAction.Retry -> "Retry"
          },
          options = UNNotificationActionOptionForeground,
        )
      },
      intentIdentifiers = emptyList<String>(),
      options = UNNotificationCategoryOptionNone,
    )

  private fun NSError.describe(): String = "$domain ${code}: $localizedDescription"

  private class Delegate : NSObject(), UNUserNotificationCenterDelegateProtocol {
    override fun userNotificationCenter(
      center: UNUserNotificationCenter,
      didReceiveNotificationResponse: UNNotificationResponse,
      withCompletionHandler: () -> Unit,
    ) {
      val content = didReceiveNotificationResponse.notification.request.content
      val action = NotificationAction.entries
        .firstOrNull { it.name == didReceiveNotificationResponse.actionIdentifier }
      taps.trySend(
        NotificationTap(
          taskKey = (content.userInfo[USER_INFO_TASK] as? String)?.let { TaskKey.decode(it) },
          action = action,
          path = content.userInfo[USER_INFO_PATH] as? String,
        ),
      )
      withCompletionHandler()
    }

    // Shown in front too, which only happens when the user turned off "only in the background".
    override fun userNotificationCenter(
      center: UNUserNotificationCenter,
      willPresentNotification: UNNotification,
      withCompletionHandler: (UNNotificationPresentationOptions) -> Unit,
    ) {
      withCompletionHandler(
        UNNotificationPresentationOptionBanner or UNNotificationPresentationOptionList
      )
    }
  }

  private const val CATEGORY_COMPLETED = "completed"
  private const val CATEGORY_FAILED = "failed"
  private const val USER_INFO_TASK = "taskKey"
  private const val USER_INFO_PATH = "path"
  private const val PAUSED_ID = "background-paused"
  private const val PAUSED_NOTICE_DELAY_SECONDS = 60.0
}

/**
 * A tap on one of Ketch's notifications.
 *
 * @property taskKey task the notification was about, or `null` for a summary.
 * @property action button that was tapped, or `null` for the notification itself.
 * @property path the downloaded file on this device, for [NotificationAction.Open] and
 *   [NotificationAction.Share].
 */
internal class NotificationTap(
  val taskKey: TaskKey?,
  val action: NotificationAction?,
  val path: String?,
)

/**
 * Reports what happens on the devices [controller] watches, as the notification settings
 * allow: events go to the app while Ketch is in front and become notifications otherwise. The
 * app badge counts failed downloads, and a tapped notification opens its task or runs its
 * button. Runs until the controller closes.
 *
 * @param fileActions opens and shares downloaded files, for the buttons of finished downloads.
 * @return the events for `App(activityEvents = …)`.
 */
internal fun IosNotifier.reportActivity(
  controller: AppController,
  fileActions: () -> FileActions?,
): Flow<ActivityEvent> {
  install()
  val manager = controller.instanceManager
  val devices = ActivityRouting.devices(manager)
  val monitor = ActivityMonitor(devices, controller.scope)
  val toasts = Channel<ActivityEvent>(Channel.UNLIMITED)
  controller.scope.launch {
    monitor.events.collect { event ->
      if (event is ActivityEvent.Added) requestAuthorization()
      val inFront = UIApplication.sharedApplication.applicationState !=
        UIApplicationState.UIApplicationStateBackground
      val settings = controller.appSettings.config.notifications
      val delivery = ActivityRouting.deliveryOf(event, settings, inFront)
      if (delivery.toast) toasts.send(event)
      if (delivery.notify) {
        ActivityRouting.copyOf(event, ActivityRouting.deviceNameOf(event, manager))
          ?.let { notify(event, it) }
      }
    }
  }
  controller.scope.launch { ActivityRouting.failures(devices).collect { setBadge(it) } }
  controller.scope.launch {
    tapped.collect { tap ->
      val actions = fileActions()
      when {
        tap.path != null && actions != null && tap.action == NotificationAction.Open ->
          launch { controller.messages.runFileAction { actions.open(tap.path) } }
        tap.path != null && actions != null && tap.action == NotificationAction.Share ->
          launch { controller.messages.runFileAction { actions.share(tap.path) } }
        else -> controller.state.open(tap)
      }
    }
  }
  return toasts.receiveAsFlow()
}

private suspend fun MessageCenter.runFileAction(action: suspend () -> Unit) {
  try {
    action()
  } catch (e: FileActionException) {
    post(MessageLevel.Error, e.message ?: "Couldn't open the file", cause = e)
  }
}

private fun AppState.open(tap: NotificationTap) {
  val key = tap.taskKey ?: return
  val entry = instances.value.firstOrNull { it.deviceId == key.deviceId } ?: return
  if (entry != activeInstance.value) switchInstance(entry)
  inspect(key)
  if (tap.action != NotificationAction.Retry) return
  val task = entry.instance.tasks.value.firstOrNull { it.taskId == key.taskId } ?: return
  retry(task)
}
