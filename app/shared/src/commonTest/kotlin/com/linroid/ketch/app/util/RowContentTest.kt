package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.state.DeviceInfo
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.RowCapabilities
import kotlinx.datetime.TimeZone
import kotlinx.datetime.UtcOffset
import kotlinx.datetime.asTimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class RowContentTest {
  private val now = Instant.parse("2026-10-01T19:48:00Z")
  private val request = DownloadRequest("https://releases.example.com/ubuntu.iso")
  private val local = DeviceInfo("This Mac", RowCapabilities.local())
  private val remote = DeviceInfo("NAS-Basement", RowCapabilities.remote())
  private val context = RowContext(local, now, TimeZone.UTC, DownloadConfig())

  @Test
  fun rowContent_downloading_showsConnectionsSpeedAndTimeLeft() {
    val state = DownloadState.Downloading(DownloadProgress(1024, 4096, 1024))
    val segments = listOf(segment(0, 1024, done = true), segment(1024, 3072), segment(3072, 4096))

    val content = rowContent(request, state, now, context, segments)

    assertEquals(RowStatus.Downloading, content.status)
    assertEquals("2 connections · releases.example.com", content.detail)
    assertEquals("1.00/4.00 KB", content.size)
    assertEquals("1.0 KB/s", content.speed)
    assertEquals("3s", content.time)
    assertEquals(0.25f, content.progress)
    assertFalse(content.limited)
    assertEquals(RowAction.Pause, content.primary)
  }

  @Test
  fun rowContent_downloadingInSlowLane_saysLimited() {
    val state = DownloadState.Downloading(DownloadProgress(0, 0, 0))

    val content = rowContent(request, state, now, context.copy(slowLane = true))

    assertEquals("releases.example.com · limited by Slow lane", content.detail)
    assertEquals("0 B/s", content.speed)
    assertEquals("–", content.time)
    assertEquals("–", content.size)
    assertTrue(content.limited)
  }

  @Test
  fun rowContent_downloadingWithTaskLimit_isLimited() {
    val limitedRequest = request.copy(speedLimit = SpeedLimit.mbps(2))
    val state = DownloadState.Downloading(DownloadProgress(0, 100, 10))

    assertTrue(rowContent(limitedRequest, state, now, context).limited)
  }

  @Test
  fun rowContent_downloadingTorrent_countsFiles() {
    val magnet = DownloadRequest("magnet:?xt=urn:btih:c12fe1c06bba254a9dc9f519b335aa7c1367a88a")
    val state = DownloadState.Downloading(DownloadProgress(0, 300, 10))
    val files = listOf(segment(0, 100), segment(100, 200), segment(200, 300))

    assertEquals("3 files", rowContent(magnet, state, now, context, files).detail)
  }

  @Test
  fun rowContent_stalledLongerThanThreshold_offersReconnect() {
    val state = DownloadState.Downloading(DownloadProgress(1024, 4096, 0))

    val stalled = rowContent(request, state, now, context, stalledFor = 12.seconds)
    val brief = rowContent(request, state, now, context, stalledFor = 3.seconds)

    assertEquals(RowStatus.Stalled, stalled.status)
    assertEquals("Stalled · no data for 12s", stalled.detail)
    assertEquals("0 B/s", stalled.speed)
    assertEquals("–", stalled.time)
    assertEquals(RowAction.Reconnect, stalled.primary)
    assertEquals(RowStatus.Downloading, brief.status)
  }

  @Test
  fun rowContent_paused_showsPercent() {
    val state = DownloadState.Paused(DownloadProgress(29, 100))

    val content = rowContent(request, state, now, context)

    assertEquals("Paused · 29%", content.detail)
    assertEquals("", content.speed)
    assertEquals(RowAction.Resume, content.primary)
  }

  @Test
  fun rowContent_progressPastTotal_staysAtHundredPercent() {
    val state = DownloadState.Paused(DownloadProgress(150, 100))

    val content = rowContent(request, state, now, context)

    assertEquals("Paused · 100%", content.detail)
    assertEquals(1f, content.progress)
  }

  @Test
  fun rowContent_queued_explainsWait() {
    val running = listOf(DownloadRequest("https://a.com/1"), DownloadRequest("https://b.com/2"))

    val waiting = rowContent(request, DownloadState.Queued, now, context.copy(running = running))
    val unknown = rowContent(request, DownloadState.Queued, now, context.copy(config = null))

    assertEquals("Waiting for a free slot (2 of 2 in use)", waiting.detail)
    assertEquals("–", waiting.size)
    assertEquals(RowAction.StartNow, waiting.primary)
    assertEquals("Waiting to start", unknown.detail)
  }

  @Test
  fun rowContent_scheduledAtTime_countsDown() {
    val state = DownloadState.Scheduled(
      DownloadSchedule.AtTime(Instant.parse("2026-10-01T23:00:00Z"))
    )

    val content = rowContent(request, state, now, context)

    assertEquals("Starts today at 23:00 · in 3h 12m", content.detail)
    assertEquals(RowAction.StartNow, content.primary)
  }

  @Test
  fun rowContent_scheduledTomorrowAndLater_namesTheDay() {
    val tomorrow = DownloadSchedule.AtTime(Instant.parse("2026-10-02T08:00:00Z"))
    val later = DownloadSchedule.AtTime(Instant.parse("2026-10-05T08:05:00Z"))

    val tomorrowContent = rowContent(request, DownloadState.Scheduled(tomorrow), now, context)
    val laterContent = rowContent(request, DownloadState.Scheduled(later), now, context)

    assertEquals("Starts tomorrow at 08:00 · in 12h 12m", tomorrowContent.detail)
    assertEquals("Starts Oct 5 at 08:05 · in 3d 12h", laterContent.detail)
  }

  @Test
  fun rowContent_scheduledInThePast_dropsCountdown() {
    val past = DownloadSchedule.AtTime(Instant.parse("2026-10-01T19:00:00Z"))

    assertEquals(
      "Starts today at 19:00",
      rowContent(request, DownloadState.Scheduled(past), now, context).detail
    )
  }

  @Test
  fun rowContent_scheduledAfterDelay_keepsDelay() {
    val halfHour = DownloadState.Scheduled(DownloadSchedule.AfterDelay(30.minutes))
    val longer = DownloadState.Scheduled(DownloadSchedule.AfterDelay(1.hours + 30.minutes))
    val conditions = DownloadState.Scheduled(DownloadSchedule.Immediate)

    assertEquals("Starts after 30 min", rowContent(request, halfHour, now, context).detail)
    assertEquals("Starts after 1h 30m", rowContent(request, longer, now, context).detail)
    assertEquals("Waiting for conditions", rowContent(request, conditions, now, context).detail)
  }

  @Test
  fun rowContent_scheduledOnRemote_hasNoPrimary() {
    val state = DownloadState.Scheduled(DownloadSchedule.AfterDelay(30.minutes))

    assertNull(rowContent(request, state, now, context.copy(device = remote)).primary)
  }

  @Test
  fun rowContent_completed_showsTransferSummaryAndHost() {
    val state = DownloadState.Completed(
      outputPath = "/tmp/ubuntu.iso",
      totalBytes = 10_485_760,
      downloadTime = 4.seconds,
    )

    val content = rowContent(request, state, now, context)

    assertEquals(RowStatus.Completed, content.status)
    assertEquals("took 4s · avg 2.5 MB/s · releases.example.com", content.detail)
    assertEquals("10.0 MB", content.size)
    assertEquals("took 4s", content.time)
    assertEquals(RowAction.Open, content.primary)
  }

  @Test
  fun rowContent_completedOnRemote_namesDevice() {
    val state = DownloadState.Completed("/srv/ubuntu.iso", totalBytes = 1024)

    val content = rowContent(request, state, now, context.copy(device = remote))

    assertEquals("Saved on NAS-Basement", content.detail)
    assertEquals("1.0 KB", content.size)
    assertEquals(RowAction.CopyPath, content.primary)
  }

  @Test
  fun rowContent_completedFileMissing_offersDownloadAgain() {
    val state = DownloadState.Completed("/tmp/ubuntu.iso")

    val content = rowContent(request, state, now, context, fileMissing = true)

    assertEquals(RowStatus.FileMissing, content.status)
    assertEquals("File moved or deleted", content.detail)
    assertEquals("–", content.size)
    assertEquals(RowAction.DownloadAgain, content.primary)
  }

  @Test
  fun rowContent_failed_leadsWithErrorTitle() {
    val state = DownloadState.Failed(KetchError.Http(403))

    val content = rowContent(request, state, now, context)

    assertEquals("Access denied (403) · the link may have expired", content.detail)
    assertEquals("Access denied (403)", content.error?.title)
    assertEquals(RowAction.EditLink, content.primary)
  }

  @Test
  fun rowContent_failedNetwork_usesDeviceRetryCount() {
    val state = DownloadState.Failed(KetchError.Network())

    val retrying = context.copy(config = DownloadConfig(retryCount = 5))

    val content = rowContent(request, state, now, retrying)

    assertNotNull(content.error)
    assertEquals("Couldn't reach releases.example.com. Ketch retried 5 times.", content.error.hint)
  }

  @Test
  fun rowContent_canceled_downloadsAgain() {
    val content = rowContent(request, DownloadState.Canceled, now, context)

    assertEquals("Canceled · releases.example.com", content.detail)
    assertEquals(RowAction.DownloadAgain, content.primary)
  }

  @Test
  fun formatAdded_pastDates_namesDayOrDate() {
    assertEquals("Today 11:42", formatAdded(Instant.parse("2026-10-01T11:42:00Z"), now, UTC))
    assertEquals("Yesterday", formatAdded(Instant.parse("2026-09-30T23:59:00Z"), now, UTC))
    assertEquals("Sep 28", formatAdded(now - 3.days, now, UTC))
    assertEquals("Sep 28, 2025", formatAdded(now - 368.days, now, UTC))
  }

  @Test
  fun formatAdded_otherTimeZone_usesLocalDay() {
    val tokyo = UtcOffset(hours = 9).asTimeZone()

    assertEquals("Yesterday", formatAdded(Instant.parse("2026-10-01T11:42:00Z"), now, tokyo))
    assertEquals("Today 01:00", formatAdded(Instant.parse("2026-10-01T16:00:00Z"), now, tokyo))
  }

  private fun segment(start: Long, end: Long, done: Boolean = false): Segment =
    Segment(index = 0, start = start, end = end - 1, downloadedBytes = if (done) end - start else 0)

  private companion object {
    val UTC = TimeZone.UTC
  }

  @Test
  fun formatSizeOf_sharedUnit_usesTheTotalsUnit() {
    assertEquals("0.49/1.20 GB", formatSizeOf(526_385_152, 1_288_490_188))
    assertEquals("3.51/13.0 GB", formatSizeOf(3_768_000_000, 13L shl 30))
    assertEquals("138/512 MB", formatSizeOf(145_012_736, 512L shl 20))
    assertEquals("512/1000 B", formatSizeOf(512, 1000))
  }

  @Test
  fun formatSizeOf_ofSeparator_readsAsProse() {
    assertEquals("1.00 of 4.00 GB", formatSizeOf(1L shl 30, 4L shl 30, separator = " of "))
  }

  @Test
  fun formatSizeOf_downloadedBeyondTheTotal_capsAtTheTotal() {
    assertEquals("1.00/1.00 KB", formatSizeOf(2048, 1024))
  }
}
