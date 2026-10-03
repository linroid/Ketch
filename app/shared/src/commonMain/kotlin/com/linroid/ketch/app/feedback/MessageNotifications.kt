package com.linroid.ketch.app.feedback

import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.config.NotificationSettings
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.time.Instant

/**
 * Posts and withdraws the system notifications of messages posted with [AppMessage.notify], one
 * implementation per platform that raises them.
 */
interface MessageNotifier {
  /** Posts [copy] for [message]. */
  fun post(message: AppMessage, copy: NotificationCopy)

  /** Takes back the notification posted for the message with [id], where the platform can. */
  fun withdraw(id: Long)
}

/**
 * How the hosts raise system notifications for the messages posted with [AppMessage.notify],
 * such as Discover waiting for the user's OK to open a website, which would otherwise wait unseen
 * while the app is not in front. The hosts share it so every platform decides alike.
 */
object MessageNotifications {
  /**
   * Whether [message] becomes a system notification: it was posted with [AppMessage.notify] and
   * the app is not [inFront], where its toast shows it. Such a message is a request that waits
   * for the user, so the download reports of [settings] neither silence it nor show it while
   * the app is in front; only a muted device's messages never notify.
   */
  fun notifies(message: AppMessage, settings: NotificationSettings, inFront: Boolean): Boolean {
    if (!message.notify) return false
    if (message.deviceId?.let { it in settings.mutedDevices } == true) return false
    return !inFront
  }

  /** What the notification for [message] says, in the language of the app's window. */
  suspend fun copyOf(message: AppMessage): NotificationCopy = NotificationCopy(
    title = message.title.load(),
    body = message.detail?.load().orEmpty(),
  )

  /**
   * Posts each message [messages] shows to [notifier] once [notifies] allows it: when it is
   * posted, or later, while it still shows, when the app leaves the front. Each message
   * notifies at most once. The notification is withdrawn once the message leaves the screen:
   * answered, withdrawn or dismissed. Runs until canceled, then withdraws the notifications
   * still up, as the buttons they lead to go with the app.
   *
   * @param settings the notification settings, read each time a message may notify.
   * @param inFront whether the app is in front, as it changes.
   */
  suspend fun follow(
    messages: MessageCenter,
    notifier: MessageNotifier,
    settings: () -> NotificationSettings,
    inFront: Flow<Boolean>,
  ): Nothing {
    val posted = LinkedHashSet<Long>()
    try {
      combine(messages.active, inFront.distinctUntilChanged(), ::Pair).collect { (active, front) ->
        for (message in active) {
          if (message.id in posted || !notifies(message, settings(), front)) continue
          notifier.post(message, copyOf(message))
          posted += message.id
        }
        val shown = active.mapTo(HashSet()) { it.id }
        val gone = posted.filterNot { it in shown }
        posted.removeAll(gone)
        gone.forEach(notifier::withdraw)
      }
      awaitCancellation()
    } finally {
      posted.forEach(notifier::withdraw)
    }
  }

  /**
   * Opens the message whose notification was tapped: runs its first action, the one that shows
   * what it is about, such as Review, and takes it off the screen, as clicking the action on its
   * toast does. Does nothing once the message is gone, or when the message with [id] is not the
   * one posted at [postedAt], as after the app started again, which numbers messages anew.
   *
   * @param postedAt when the message was posted, to the millisecond.
   * @return whether the message was still on screen.
   */
  fun open(messages: MessageCenter, id: Long, postedAt: Instant): Boolean {
    val message = messages.active.value.firstOrNull {
      it.id == id && it.at.toEpochMilliseconds() == postedAt.toEpochMilliseconds()
    } ?: return false
    messages.dismiss(id)
    message.actions.firstOrNull()?.onClick?.invoke()
    return true
  }
}
