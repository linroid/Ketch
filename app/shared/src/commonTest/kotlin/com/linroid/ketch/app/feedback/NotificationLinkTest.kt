package com.linroid.ketch.app.feedback

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskKey
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class NotificationLinkTest {

  private val request = DownloadRequest("https://example.com/files/ubuntu.iso")
  private val finished = DownloadState.Completed("/downloads/ubuntu.iso", 1024L, 5.seconds)

  private fun completed(deviceId: String) =
    ActivityEvent.Completed(TaskKey(deviceId, "t1"), request, finished)

  @Test
  fun of_completed_showsTheTask() {
    val link = NotificationLink.of(completed(LOCAL_DEVICE_ID))

    assertEquals(
      NotificationLink(TaskKey(LOCAL_DEVICE_ID, "t1"), filter = null, deviceId = LOCAL_DEVICE_ID),
      link,
    )
  }

  @Test
  fun of_failed_showsTheTaskWithoutRetrying() {
    val failed = ActivityEvent.Failed(
      TaskKey("nas", "t2"),
      request,
      DownloadState.Failed(KetchError.Network()),
    )

    val link = NotificationLink.of(failed)

    assertEquals(TaskKey("nas", "t2"), link.taskKey)
    assertEquals(false, link.retry)
  }

  @Test
  fun of_summaries_showTheFinishedTabOfTheirDevice() {
    val drained = NotificationLink.of(ActivityEvent.QueueDrained("nas", files = 3, bytes = 0))
    val batch = NotificationLink.of(ActivityEvent.CompletedBatch(listOf(completed("nas"))))

    val expected = NotificationLink(taskKey = null, filter = StatusFilter.Done, deviceId = "nas")
    assertEquals(expected, drained)
    assertEquals(expected, batch)
  }

  @Test
  fun filePathOf_finishedOnThisDevice_isItsFile() {
    assertEquals("/downloads/ubuntu.iso", NotificationLink.filePathOf(completed(LOCAL_DEVICE_ID)))
  }

  @Test
  fun filePathOf_finishedOnAnotherDevice_isNull() {
    assertNull(NotificationLink.filePathOf(completed("nas")))
  }

  @Test
  fun filePathOf_notAFinishedDownload_isNull() {
    val failed = ActivityEvent.Failed(
      TaskKey(LOCAL_DEVICE_ID, "t2"),
      request,
      DownloadState.Failed(KetchError.Network()),
    )

    assertNull(NotificationLink.filePathOf(failed))
  }
}
