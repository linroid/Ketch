package com.linroid.ketch.app.desktop

import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.isTraySupported
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.ActivityEvent
import com.linroid.ketch.app.feedback.NotificationCopy
import com.linroid.ketch.app.feedback.SystemNotifier
import kotlin.concurrent.thread

/**
 * Posts the desktop app's notifications from the tray icon of [trayState]. Without a tray, as on
 * GNOME without AppIndicator, Linux posts them with `notify-send`, and other systems post none.
 *
 * AWT notifications have no buttons, so [NotificationCopy.actions] are left out.
 *
 * @param trayState state of the [KetchTray] icon.
 * @param traySupported whether the system has a tray; checked once, at creation.
 */
class TrayNotifier(
  private val trayState: TrayState,
  private val traySupported: Boolean = isTraySupported,
) : SystemNotifier {
  private val log = KetchLogger("TrayNotifier")
  private val linux = System.getProperty("os.name").startsWith("Linux")

  override fun notify(event: ActivityEvent, copy: NotificationCopy) {
    when {
      traySupported -> trayState.sendNotification(
        Notification(copy.title, copy.body, notificationType(event)),
      )
      linux -> notifySend(notifySendCommand(copy))
      else -> log.d { "No tray to post \"${copy.title}\" from" }
    }
  }

  private fun notifySend(command: List<String>) {
    thread(isDaemon = true, name = "ketch-notify-send") {
      try {
        ProcessBuilder(command)
          .redirectOutput(ProcessBuilder.Redirect.DISCARD)
          .redirectError(ProcessBuilder.Redirect.DISCARD)
          .start()
          .waitFor()
      } catch (e: Exception) {
        log.d { "Couldn't run notify-send: ${e.describeCauses()}" }
      }
    }
  }
}

/** How a notification for [event] looks: failures as errors, a lost device as a warning. */
internal fun notificationType(event: ActivityEvent): Notification.Type = when (event) {
  is ActivityEvent.Failed -> Notification.Type.Error
  is ActivityEvent.DeviceOffline -> Notification.Type.Warning
  else -> Notification.Type.Info
}

/**
 * The `notify-send` command line for [copy]. The text follows `--`, so a title or body that starts
 * with a dash is not read as an option.
 */
internal fun notifySendCommand(copy: NotificationCopy): List<String> =
  listOf("notify-send", "--app-name=Ketch", "--", copy.title, copy.body)
