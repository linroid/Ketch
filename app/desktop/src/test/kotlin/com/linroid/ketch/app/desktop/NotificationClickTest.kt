package com.linroid.ketch.app.desktop

import androidx.compose.ui.window.TrayState
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.feedback.ActivityEvent
import com.linroid.ketch.app.feedback.NotificationCopy
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.TaskKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class NotificationClickTest {

  private val copy = NotificationCopy("Download complete", "ubuntu.iso")
  private val completed = ActivityEvent.Completed(
    TaskKey(LOCAL_DEVICE_ID, "t1"),
    DownloadRequest("https://example.com/ubuntu.iso"),
    DownloadState.Completed("/downloads/ubuntu.iso", 1024L, 5.seconds),
  )

  private fun notifier() = TrayNotifier(TrayState(), traySupported = true, mac = false)

  @Test
  fun trayMessageClicked_reportsTheLastNotificationPosted() = runTest {
    val notifier = notifier()
    notifier.post(copy)
    notifier.notify(completed, copy)

    notifier.trayMessageClicked()

    assertEquals(NotificationClick.Activity(completed), notifier.clicks.first())
  }

  @Test
  fun trayMessageClicked_again_showsTheWindow() = runTest {
    val notifier = notifier()
    notifier.notify(completed, copy)
    notifier.trayMessageClicked()
    notifier.clicks.first()

    notifier.trayMessageClicked()

    assertEquals(NotificationClick.App, notifier.clicks.first())
  }

  @Test
  fun trayClicks_actionWithoutMouse_isANotificationClick() {
    var now = 0L
    val clicks = TrayClicks { now }

    assertFalse(clicks.onIcon())
    clicks.touched()
    now += 5_000_000_000L
    assertFalse(clicks.onIcon())
  }

  @Test
  fun trayClicks_actionRightAfterMouse_isAnIconClick() {
    var now = 0L
    val clicks = TrayClicks { now }

    clicks.touched()
    now += 200_000_000L

    assertTrue(clicks.onIcon())
  }

  @Test
  fun notifySendCommand_clickLabel_addsTheDefaultAction() {
    val command = notifySendCommand(copy, clickLabel = "Open")

    assertEquals(
      listOf(
        "notify-send",
        "--app-name=Ketch",
        "--action=default=Open",
        "--",
        "Download complete",
        "ubuntu.iso",
      ),
      command,
    )
  }
}
