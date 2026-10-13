package com.linroid.ketch.app.desktop

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.isTraySupported
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.ActivityEvent
import com.linroid.ketch.app.feedback.ActivityRouting
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.feedback.MessageNotifier
import com.linroid.ketch.app.feedback.NotificationCopy
import com.linroid.ketch.app.feedback.NotificationLink
import com.linroid.ketch.app.feedback.SystemNotifier
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlin.concurrent.thread
import kotlin.time.Instant

/**
 * What clicking one of the desktop app's notifications asks for, from [TrayNotifier.clicks].
 */
internal sealed interface NotificationClick {
  /**
   * A notification about [event]: opens a finished download's file on this computer
   * ([NotificationLink.filePathOf]), else shows its task ([NotificationLink.of]).
   */
  data class Activity(val event: ActivityEvent) : NotificationClick

  /** The notification of the app message [id], posted at [postedAt]. */
  data class Message(val id: Long, val postedAt: Instant) : NotificationClick

  /** A notification about nothing in particular, such as a pairing request: shows the window. */
  data object App : NotificationClick
}

/**
 * Posts the desktop app's notifications and reports clicks on them as [clicks].
 *
 * macOS posts them through [MacNotifications] when Ketch runs as an app, otherwise from the tray
 * icon of [trayState], whose notifications report a click through [trayMessageClicked]: the
 * system only says that one of them was clicked, so it is taken to be the last one posted.
 * Without a tray, as on GNOME without AppIndicator, Linux posts them with `notify-send`, and
 * other systems post none.
 *
 * Desktop notifications have no buttons, so [NotificationCopy.actions] are left out, and cannot
 * be taken back, so [withdraw] leaves them up.
 *
 * @param trayState state of the [KetchTray] icon.
 * @param traySupported whether the system has a tray; checked once, at creation.
 * @param mac whether [MacNotifications] post the notifications.
 */
class TrayNotifier(
  private val trayState: TrayState,
  private val traySupported: Boolean = isTraySupported,
  private val mac: Boolean = MacNotifications.available,
) : SystemNotifier, MessageNotifier {
  private val log = KetchLogger("TrayNotifier")
  private val linux = System.getProperty("os.name").startsWith("Linux")
  private val clicked = Channel<NotificationClick>(Channel.UNLIMITED)

  // What the notifications macOS shows are about, by name, newest last.
  private val named = object : LinkedHashMap<String, NotificationClick>() {
    override fun removeEldestEntry(eldest: Map.Entry<String, NotificationClick>) =
      size > MAX_REMEMBERED
  }

  // What the tray's last notification is about.
  @Volatile
  private var lastTrayClick: NotificationClick = NotificationClick.App

  // Cleared once notify-send turns out not to know --action.
  @Volatile
  private var notifySendActions = true

  init {
    if (mac) {
      MacNotifications.onClick = { name ->
        val click = synchronized(named) { named.remove(name) } ?: NotificationClick.App
        clicked.trySend(click)
      }
    }
  }

  /** Clicks on the notifications, oldest first; each goes to one collector. */
  internal val clicks: Flow<NotificationClick> = clicked.receiveAsFlow()

  override fun notify(event: ActivityEvent, copy: NotificationCopy) {
    // A task's newer notification replaces its older one, and a summary the previous summary.
    val name = ActivityRouting.taskKeyOf(event)?.let { "task:${it.encode()}" }
      ?: "device:${ActivityRouting.deviceIdOf(event)}"
    post(copy, notificationType(event), NotificationClick.Activity(event), name)
  }

  override fun post(message: AppMessage, copy: NotificationCopy) {
    val click = NotificationClick.Message(message.id, message.at)
    post(copy, notificationType(message.level), click, "message:${message.id}")
  }

  // A tray notification stays until the system hides it.
  override fun withdraw(id: Long) = Unit

  /**
   * Posts [copy], which is about no [ActivityEvent], such as a pairing request; clicking it shows
   * the window.
   */
  fun post(copy: NotificationCopy, type: Notification.Type = Notification.Type.Info) {
    post(copy, type, NotificationClick.App, "app")
  }

  /**
   * Reports a click on the tray icon's notification, the last one it showed; once reported, a
   * click shows the window.
   */
  internal fun trayMessageClicked() {
    clicked.trySend(lastTrayClick)
    lastTrayClick = NotificationClick.App
  }

  private fun post(
    copy: NotificationCopy,
    type: Notification.Type,
    click: NotificationClick,
    name: String,
  ) {
    when {
      mac -> {
        synchronized(named) {
          // Moved to the end, so a newer notification under the name is forgotten last.
          named.remove(name)
          named[name] = click
        }
        MacNotifications.post(name, copy.title, copy.body)
      }
      traySupported -> {
        lastTrayClick = click
        trayState.sendNotification(Notification(copy.title, copy.body, type))
      }
      linux -> notifySend(copy, click)
      else -> log.d { "No tray to post \"${copy.title}\" from" }
    }
  }

  private fun notifySend(copy: NotificationCopy, click: NotificationClick) {
    thread(isDaemon = true, name = "ketch-notify-send") {
      try {
        if (notifySendActions) {
          when (val result = runNotifySend(notifySendCommand(copy, clickLabel = APP_NAME))) {
            null -> {
              // Older versions refuse --action, and post nothing.
              log.d { "notify-send has no --action, posting without it" }
              notifySendActions = false
              runNotifySend(notifySendCommand(copy))
            }
            NOTIFY_SEND_DEFAULT_ACTION -> clicked.trySend(click)
            else -> log.v { "Notification closed: $result" }
          }
        } else {
          runNotifySend(notifySendCommand(copy))
        }
      } catch (e: Exception) {
        log.d { "Couldn't run notify-send: ${e.describeCauses()}" }
      }
    }
  }

  // What the command printed, trimmed, or `null` when it failed. With --action, notify-send
  // waits until the notification is closed and prints the action that closed it.
  private fun runNotifySend(command: List<String>): String? {
    val process = ProcessBuilder(command)
      .redirectError(ProcessBuilder.Redirect.DISCARD)
      .start()
    val output = process.inputStream.bufferedReader().use { it.readText() }
    return if (process.waitFor() == 0) output.trim() else null
  }

  private companion object {
    const val APP_NAME = "Ketch"

    // Enough for the notifications still on screen, which macOS also keeps in its list.
    const val MAX_REMEMBERED = 64
  }
}

/** How a notification for [event] looks: failures as errors, a lost device as a warning. */
internal fun notificationType(event: ActivityEvent): Notification.Type = when (event) {
  is ActivityEvent.Failed -> Notification.Type.Error
  is ActivityEvent.DeviceOffline -> Notification.Type.Warning
  else -> Notification.Type.Info
}

/** How a notification for a message of [level] looks: errors and warnings stand out. */
internal fun notificationType(level: MessageLevel): Notification.Type = when (level) {
  MessageLevel.Error -> Notification.Type.Error
  MessageLevel.Warning -> Notification.Type.Warning
  MessageLevel.Info, MessageLevel.Success -> Notification.Type.Info
}

/**
 * The `notify-send` command line for [copy]. The text follows `--`, so a title or body that starts
 * with a dash is not read as an option. With a [clickLabel], clicking the notification runs its
 * default action, named so where the notification server lists actions, which `notify-send`
 * prints once the notification closes.
 */
internal fun notifySendCommand(copy: NotificationCopy, clickLabel: String? = null): List<String> =
  listOfNotNull(
    "notify-send",
    "--app-name=Ketch",
    clickLabel?.let { "--action=$NOTIFY_SEND_DEFAULT_ACTION=$it" },
    "--",
    copy.title,
    copy.body,
  )

// The action notification servers run when the notification itself is clicked.
private const val NOTIFY_SEND_DEFAULT_ACTION = "default"
