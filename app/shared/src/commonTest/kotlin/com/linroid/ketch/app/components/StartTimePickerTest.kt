package com.linroid.ketch.app.components

import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.app.state.SpeedScheduler
import com.linroid.ketch.config.SpeedRule
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class StartTimePickerTest {

  private val zone = TimeZone.of("Europe/Berlin")

  private fun local(dateTime: String): Instant = LocalDateTime.parse(dateTime).toInstant(zone)

  private fun option(label: String, now: Instant): DownloadSchedule =
    startTimeOptions(now, zone).first { it.label == label }.schedule

  @Test
  fun startTimeOptions_tonightInTheEvening_isTheNextLocal0100() {
    assertEquals(
      DownloadSchedule.AtTime(local("2026-10-02T01:00")),
      option("Tonight 01:00", local("2026-10-01T22:30")),
    )
  }

  @Test
  fun startTimeOptions_tonightAfterMidnight_isLaterThatNight() {
    assertEquals(
      DownloadSchedule.AtTime(local("2026-10-02T01:00")),
      option("Tonight 01:00", local("2026-10-02T00:30")),
    )
  }

  @Test
  fun startTimeOptions_tonightAtExactly0100_isTheNextNight() {
    assertEquals(
      DownloadSchedule.AtTime(local("2026-10-03T01:00")),
      option("Tonight 01:00", local("2026-10-02T01:00")),
    )
  }

  @Test
  fun startTimeOptions_timedChoices_areAtTimeNeverDelays() {
    val now = local("2026-10-01T09:15")
    val options = startTimeOptions(now, zone, offPeak = local("2026-10-01T18:00"))

    assertEquals(DownloadSchedule.Immediate, options.first().schedule)
    assertEquals(DownloadSchedule.AtTime(now + 1.hours), option("In 1 hour", now))
    assertEquals(
      DownloadSchedule.AtTime(local("2026-10-02T08:00")),
      option("Tomorrow 08:00", now),
    )
    assertEquals(
      StartTimeOption("Off-peak · 18:00", DownloadSchedule.AtTime(local("2026-10-01T18:00"))),
      options.last(),
    )
  }

  @Test
  fun startTimeOptions_noOffPeak_leavesItOut() {
    assertEquals(4, startTimeOptions(local("2026-10-01T09:15"), zone).size)
  }

  @Test
  fun nextLocalTime_acrossTheAutumnClockChange_keepsTheWallClockTime() {
    // Clocks in Berlin go back at 03:00 on 25 October 2026.
    assertEquals(
      local("2026-10-25T08:00"),
      nextLocalTime(local("2026-10-24T09:00"), zone, LocalTime(8, 0)),
    )
  }

  @Test
  fun startTimeLabel_nightAfterToday_saysTonight() {
    val now = local("2026-10-01T22:30")
    assertEquals(
      "Starts 01:00 tonight",
      startTimeLabel(DownloadSchedule.AtTime(local("2026-10-02T01:00")), now, zone),
    )
    assertEquals(
      "Starts 23:00 tonight",
      startTimeLabel(DownloadSchedule.AtTime(local("2026-10-01T23:00")), now, zone),
    )
  }

  @Test
  fun startTimeLabel_laterDays_sayTodayTomorrowOrTheWeekday() {
    val now = local("2026-10-01T09:00")
    fun label(at: String) = startTimeLabel(DownloadSchedule.AtTime(local(at)), now, zone)

    assertEquals("Starts 14:00 today", label("2026-10-01T14:00"))
    assertEquals("Starts tomorrow 08:00", label("2026-10-02T08:00"))
    assertEquals("Starts Mon 08:00", label("2026-10-05T08:00"))
    assertEquals("Starts Oct 12 08:00", label("2026-10-12T08:00"))
  }

  @Test
  fun startTimeLabel_immediateOrPast_isNow() {
    val now = local("2026-10-01T09:00")
    assertEquals("Now", startTimeLabel(DownloadSchedule.Immediate, now, zone))
    assertEquals("Now", startTimeLabel(DownloadSchedule.AtTime(now - 1.minutes), now, zone))
  }

  @Test
  fun offPeakStart_insideTheSlowLaneWindow_isWhenItEnds() {
    val scheduler = SpeedScheduler { zone }
    val rules = listOf(SpeedRule(start = "09:00", end = "18:00"))

    assertEquals(
      local("2026-10-01T18:00"),
      offPeakStart(rules, local("2026-10-01T10:00"), scheduler),
    )
    assertNull(offPeakStart(rules, local("2026-10-01T20:00"), scheduler))
    assertNull(offPeakStart(emptyList(), local("2026-10-01T10:00"), scheduler))
  }
}
