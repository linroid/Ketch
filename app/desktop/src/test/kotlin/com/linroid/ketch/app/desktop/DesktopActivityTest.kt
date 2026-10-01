package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.app.feedback.ActivityEvent
import com.linroid.ketch.app.feedback.NotificationCopy
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.TaskKey
import kotlin.test.Test
import kotlin.test.assertEquals

class DesktopActivityTest {
  private fun added(name: String) = ActivityEvent.Added(
    TaskKey(LOCAL_DEVICE_ID, name),
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
}
