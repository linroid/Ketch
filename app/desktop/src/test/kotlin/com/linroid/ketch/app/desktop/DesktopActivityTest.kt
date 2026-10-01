package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.app.feedback.ActivityEvent
import com.linroid.ketch.app.feedback.NotificationCopy
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.config.NotificationSettings
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

class DesktopActivityTest {
  private fun added(name: String, deviceId: String = LOCAL_DEVICE_ID) = ActivityEvent.Added(
    TaskKey(deviceId, name),
    DownloadRequest(url = "https://example.com/$name"),
  )

  @Test
  fun addedCopy_oneDownload_namesIt() {
    assertEquals(
      NotificationCopy(title = "Download added", body = "a.iso"),
      addedCopy(listOf(added("a.iso")), deviceName = null),
    )
  }

  @Test
  fun addedCopy_manyDownloads_namesTheFirstThreeAndCountsTheRest() {
    val events = listOf("a.iso", "b.iso", "c.iso", "d.iso", "e.iso").map(::added)

    assertEquals(
      NotificationCopy(title = "5 downloads added", body = "a.iso, b.iso, c.iso and 2 more"),
      addedCopy(events, deviceName = null),
    )
  }

  @Test
  fun addedCopy_anotherDevice_namesTheDevice() {
    assertEquals(
      "On NAS: Download added",
      addedCopy(listOf(added("a.iso")), deviceName = "NAS").title,
    )
  }

  @Test
  fun announcesAdded_windowNotInFront_announces() {
    assertTrue(announcesAdded(added("a.iso"), NotificationSettings(), inFront = false))
  }

  @Test
  fun announcesAdded_windowInFront_leavesItToTheToast() {
    assertFalse(announcesAdded(added("a.iso"), NotificationSettings(), inFront = true))
  }

  @Test
  fun announcesAdded_mutedDevice_staysQuiet() {
    val settings = NotificationSettings(mutedDevices = listOf("nas"))

    assertFalse(announcesAdded(added("a.iso", deviceId = "nas"), settings, inFront = false))
  }

  @Test
  fun add_downloadsAddedTogether_postsOneNotification() = runTest {
    val posted = mutableListOf<List<String>>()
    val notices = AddedNotices(backgroundScope, window = 1.seconds) { batch ->
      posted += batch.map { it.taskKey.taskId }
    }

    notices.add(added("a.iso"))
    advanceTimeBy(500)
    notices.add(added("b.iso"))
    advanceTimeBy(600)
    runCurrent()

    assertEquals(listOf(listOf("a.iso", "b.iso")), posted)
  }

  @Test
  fun add_afterTheWindow_postsAnotherNotification() = runTest {
    val posted = mutableListOf<List<String>>()
    val notices = AddedNotices(backgroundScope, window = 1.seconds) { batch ->
      posted += batch.map { it.taskKey.taskId }
    }

    notices.add(added("a.iso"))
    advanceTimeBy(1_100)
    notices.add(added("b.iso"))
    advanceTimeBy(1_100)

    assertEquals(listOf(listOf("a.iso"), listOf("b.iso")), posted)
  }
}
