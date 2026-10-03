package com.linroid.ketch.app.ui.pulse

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.speedText
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DiskSpace
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskKey
import kotlinx.coroutines.test.runTest
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
  fun countParts_zeroCounts_leavesThemOut() = runTest {
    val parts = countParts(PulseCounts(downloading = 2, paused = 1, done = 4), failures = 0)

    assertEquals(listOf("2↓"), parts.map { it.text }.load())
    assertEquals(StatusFilter.Downloading, parts.single().filter)
    assertEquals("2 downloading", parts.single().description.load())
  }

  @Test
  fun countParts_everyStatus_readsDownloadingWaitingFailed() = runTest {
    val parts = countParts(PulseCounts(downloading = 2, waiting = 3, failed = 1), failures = 1)

    assertEquals(listOf("2↓", "3 waiting", "1 failed"), parts.map { it.text }.load())
    assertEquals(
      listOf(StatusFilter.Downloading, StatusFilter.Waiting, StatusFilter.Failed),
      parts.map { it.filter }
    )
    assertEquals(listOf(false, false, true), parts.map { it.alert })
  }

  @Test
  fun countParts_onlyCanceled_countsTheTabWithoutAlert() = runTest {
    val part = countParts(PulseCounts(failed = 1), failures = 0).single()

    assertEquals("1 failed", part.text.load())
    assertEquals(false, part.alert)
  }

  @Test
  fun countParts_matchesTheStatusTabs() = runTest {
    val states = listOf(
      ListFixtures.downloading(10),
      DownloadState.Queued,
      DownloadState.Paused(DownloadProgress(1, 10)),
      DownloadState.Canceled
    )
    val counts = PulseCounts.of(states)

    val parts = countParts(counts, failures = 0)

    parts.forEach { part ->
      assertEquals(part.filter.count(states), part.text.load().takeWhile { it.isDigit() }.toInt())
    }
  }

  @Test
  fun splitSpeed_megabytes_splitsAmountAndUnit() = runTest {
    val speed = splitSpeed(speedText(9_542_041).load())

    assertEquals("9.1", speed.amount)
    assertEquals("MB/s", speed.unit)
  }

  @Test
  fun splitSpeed_noSpace_isAllAmount() {
    assertEquals(SpeedText("9.1MB/s", ""), splitSpeed("9.1MB/s"))
  }

  @Test
  fun diskLabel_largeDisk_roundsToWholeGigabytes() = runTest {
    val disk = DiskSpace(usableBytes = 412_316_860_416, totalBytes = 994_662_584_320, "/d")

    assertEquals("384 GB free", diskLabel(disk).load())
  }

  @Test
  fun diskUsed_unknownSize_isZero() {
    assertEquals(0f, diskUsed(DiskSpace(usableBytes = 10, totalBytes = 0, directory = "/d")))
  }

  @Test
  fun healthText_embeddedDevice_namesItsSharing() = runTest {
    assertEquals("Sharing :8642", healthText(DeviceHealth.Local(sharingPort = 8642)).load())
    assertEquals("Not shared", healthText(DeviceHealth.Local()).load())
    assertEquals("Needs a token", healthText(DeviceHealth.Unauthorized).load())
  }

  @Test
  fun selectionSummary_rows_sumsSizeAndSpeed() = runTest {
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

    assertEquals("3 selected · 2.40 GB · 9.1 MB/s", summary.load())
  }

  @Test
  fun selectionSummary_unknownSizesAndNoSpeed_namesOnlyTheCount() = runTest {
    val queued = ListFixtures.row("c", DownloadState.Queued)

    assertEquals("1 selected", selectionSummary(listOf(queued), setOf(queued.key)).load())
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
  fun speedModeLabelText_fullSpeed_namesTheCapWhenThereIsOne() = runTest {
    assertEquals(
      "Full speed",
      speedModeLabelText(SpeedMode.Full, SpeedLimit.Unlimited, now, utc).load()
    )
    assertEquals(
      "Capped · 20 MB/s",
      speedModeLabelText(SpeedMode.Full, SpeedLimit.mbps(20), now, utc).load()
    )
  }

  @Test
  fun speedModeLabelText_slowLane_namesItsSpeed() = runTest {
    assertEquals(
      "Slow lane · 1 MB/s",
      speedModeLabelText(SpeedMode.SlowLane, SpeedLimit.mbps(1), now, utc).load()
    )
  }

  @Test
  fun speedModeLabelText_autoUntilLaterToday_namesTheTime() = runTest {
    val mode = SpeedMode.Auto(slowLane = true, until = now + 3.hours + 30.minutes)

    assertEquals(
      "Auto · Slow lane until 18:00",
      speedModeLabelText(mode, SpeedLimit.mbps(1), now, utc).load()
    )
  }

  @Test
  fun speedModeLabelText_autoUntilAnotherDay_namesTheWeekday() = runTest {
    val mode = SpeedMode.Auto(slowLane = false, until = now + 1.days - 6.hours)

    assertEquals(
      "Auto · Full speed until Fri 08:30",
      speedModeLabelText(mode, SpeedLimit.Unlimited, now, utc).load()
    )
  }

  @Test
  fun speedModeLabelText_autoWithoutRules_namesThePhaseOnly() = runTest {
    assertEquals(
      "Auto · Full speed",
      speedModeLabelText(SpeedMode.Auto(), SpeedLimit.Unlimited, now, utc).load()
    )
  }
}
