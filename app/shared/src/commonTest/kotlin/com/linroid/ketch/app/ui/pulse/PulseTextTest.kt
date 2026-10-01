package com.linroid.ketch.app.ui.pulse

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DiskSpace
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskKey
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class PulseTextTest {

  private val now = Instant.parse("2026-10-01T14:30:00Z")
  private val utc = TimeZone.UTC

  @Test
  fun countParts_zeroCounts_leavesThemOut() {
    val parts = countParts(PulseCounts(downloading = 2, paused = 1, done = 4), failures = 0)

    assertEquals(listOf("2↓"), parts.map { it.text })
    assertEquals(StatusFilter.Downloading, parts.single().filter)
    assertEquals("2 downloading", parts.single().description)
  }

  @Test
  fun countParts_everyStatus_readsDownloadingWaitingFailed() {
    val parts = countParts(PulseCounts(downloading = 2, waiting = 3, failed = 1), failures = 1)

    assertEquals(listOf("2↓", "3 waiting", "1 failed"), parts.map { it.text })
    assertEquals(
      listOf(StatusFilter.Downloading, StatusFilter.Waiting, StatusFilter.Failed),
      parts.map { it.filter }
    )
    assertEquals(listOf(false, false, true), parts.map { it.alert })
  }

  @Test
  fun countParts_onlyCanceled_countsTheTabWithoutAlert() {
    val part = countParts(PulseCounts(failed = 1), failures = 0).single()

    assertEquals("1 failed", part.text)
    assertEquals(false, part.alert)
  }

  @Test
  fun countParts_matchesTheStatusTabs() {
    val states = listOf(
      ListFixtures.downloading(10),
      DownloadState.Queued,
      DownloadState.Paused(DownloadProgress(1, 10)),
      DownloadState.Canceled
    )
    val counts = PulseCounts.of(states)

    val parts = countParts(counts, failures = 0)

    parts.forEach { part ->
      assertEquals(part.filter.count(states), part.text.takeWhile { it.isDigit() }.toInt())
    }
  }

  @Test
  fun speedText_megabytes_splitsAmountAndUnit() {
    val speed = speedText(9_542_041)

    assertEquals("9.1", speed.amount)
    assertEquals("MB/s", speed.unit)
    assertEquals("9.1 MB/s", speed.toString())
  }

  @Test
  fun diskLabel_largeDisk_roundsToWholeGigabytes() {
    val disk = DiskSpace(usableBytes = 412_316_860_416, totalBytes = 994_662_584_320, "/d")

    assertEquals("384 GB free", diskLabel(disk))
  }

  @Test
  fun diskUsed_unknownSize_isZero() {
    assertEquals(0f, diskUsed(DiskSpace(usableBytes = 10, totalBytes = 0, directory = "/d")))
  }

  @Test
  fun healthLabel_embeddedDevice_namesItsSharing() {
    assertEquals("Sharing :8642", healthLabel(DeviceHealth.Local(sharingPort = 8642)))
    assertEquals("Not shared", healthLabel(DeviceHealth.Local()))
    assertEquals("Needs a token", healthLabel(DeviceHealth.Unauthorized))
  }

  @Test
  fun selectionSummary_rows_sumsSizeAndSpeed() {
    val downloading = ListFixtures.row(
      "a",
      ListFixtures.downloading(downloaded = 0, total = 2_000_000_000, speed = 9_542_041)
    )
    val paused = ListFixtures.row(
      "b",
      DownloadState.Paused(DownloadProgress(1, 576_716_800))
    )
    val queued = ListFixtures.row("c", DownloadState.Queued)
    val selected = setOf(downloading.key, paused.key, queued.key)

    val summary = selectionSummary(listOf(downloading, paused, queued), selected)

    assertEquals("3 selected · 2.40 GB · 9.1 MB/s", summary)
  }

  @Test
  fun selectionSummary_unknownSizesAndNoSpeed_namesOnlyTheCount() {
    val queued = ListFixtures.row("c", DownloadState.Queued)

    assertEquals("1 selected", selectionSummary(listOf(queued), setOf(queued.key)))
  }

  @Test
  fun selectionSummary_keysNotListed_isNull() {
    val row = ListFixtures.row("a", DownloadState.Queued)

    assertNull(selectionSummary(listOf(row), emptySet()))
    assertNull(selectionSummary(listOf(row), setOf(TaskKey(LOCAL_DEVICE_ID, "gone"))))
  }

  @Test
  fun effectiveCap_slowLaneAboveStandingCap_holdsToTheCap() {
    val limit = effectiveCap(
      mode = SpeedMode.SlowLane,
      cap = SpeedLimit.Unlimited,
      slowLane = SpeedLimit.mbps(5),
      standard = SpeedLimit.mbps(2),
    )

    assertEquals(SpeedLimit.mbps(2), limit)
  }

  @Test
  fun effectiveCap_autoRuleOff_isTheStandingCapNotTheStaleConfig() {
    val limit = effectiveCap(
      mode = SpeedMode.Auto(slowLane = false),
      cap = SpeedLimit.mbps(1),
      slowLane = SpeedLimit.mbps(1),
      standard = SpeedLimit.Unlimited,
    )

    assertEquals(SpeedLimit.Unlimited, limit)
  }

  @Test
  fun effectiveCap_fullSpeed_isTheDevicesLimit() {
    val limit = effectiveCap(
      mode = SpeedMode.Full,
      cap = SpeedLimit.mbps(20),
      slowLane = SpeedLimit.mbps(1),
      standard = SpeedLimit.Unlimited,
    )

    assertEquals(SpeedLimit.mbps(20), limit)
  }

  @Test
  fun speedModeLabel_fullSpeed_namesTheCapWhenThereIsOne() {
    assertEquals("Full speed", speedModeLabel(SpeedMode.Full, SpeedLimit.Unlimited, now, utc))
    assertEquals(
      "Capped · 20 MB/s",
      speedModeLabel(SpeedMode.Full, SpeedLimit.mbps(20), now, utc)
    )
  }

  @Test
  fun speedModeLabel_slowLane_namesItsSpeed() {
    assertEquals(
      "Slow lane · 1 MB/s",
      speedModeLabel(SpeedMode.SlowLane, SpeedLimit.mbps(1), now, utc)
    )
  }

  @Test
  fun speedModeLabel_autoUntilLaterToday_namesTheTime() {
    val mode = SpeedMode.Auto(slowLane = true, until = now + 3.hours + 30.minutes)

    assertEquals(
      "Auto · Slow lane until 18:00",
      speedModeLabel(mode, SpeedLimit.mbps(1), now, utc)
    )
  }

  @Test
  fun speedModeLabel_autoUntilAnotherDay_namesTheWeekday() {
    val mode = SpeedMode.Auto(slowLane = false, until = now + 1.days - 6.hours)

    assertEquals(
      "Auto · Full speed until Fri 08:30",
      speedModeLabel(mode, SpeedLimit.Unlimited, now, utc)
    )
  }

  @Test
  fun speedModeLabel_autoWithoutRules_namesThePhaseOnly() {
    assertEquals(
      "Auto · Full speed",
      speedModeLabel(SpeedMode.Auto(), SpeedLimit.Unlimited, now, utc)
    )
  }
}
