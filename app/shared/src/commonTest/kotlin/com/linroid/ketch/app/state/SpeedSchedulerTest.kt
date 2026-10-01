package com.linroid.ketch.app.state

import com.linroid.ketch.config.SpeedRule
import com.linroid.ketch.config.Weekday
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toInstant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class SpeedSchedulerTest {

  private val zone = TimeZone.of("America/New_York")
  private val scheduler = SpeedScheduler { zone }

  private val weekdays = setOf(
    Weekday.Monday,
    Weekday.Tuesday,
    Weekday.Wednesday,
    Weekday.Thursday,
    Weekday.Friday
  )
  private val workHours = SpeedRule(days = weekdays, start = "09:00", end = "18:00")
  private val nights = SpeedRule(start = "22:00", end = "06:00")

  private fun local(dateTime: String): Instant = LocalDateTime.parse(dateTime).toInstant(zone)

  @Test
  fun evaluate_noRules_fullSpeedWithoutEnd() {
    assertEquals(
      SpeedWindow(slowLane = false, until = null),
      scheduler.evaluate(emptyList(), local("2026-09-30T10:00"))
    )
  }

  @Test
  fun evaluate_insideRule_slowLaneUntilItEnds() {
    assertEquals(
      SpeedWindow(slowLane = true, until = local("2026-09-30T18:00")),
      scheduler.evaluate(listOf(workHours), local("2026-09-30T10:00"))
    )
  }

  @Test
  fun evaluate_beforeRule_fullSpeedUntilItStarts() {
    assertEquals(
      SpeedWindow(slowLane = false, until = local("2026-09-30T09:00")),
      scheduler.evaluate(listOf(workHours), local("2026-09-30T08:59"))
    )
  }

  @Test
  fun evaluate_atEnd_fullSpeedUntilNextStart() {
    assertEquals(
      SpeedWindow(slowLane = false, until = local("2026-10-01T09:00")),
      scheduler.evaluate(listOf(workHours), local("2026-09-30T18:00"))
    )
  }

  @Test
  fun evaluate_fridayEvening_nextStartIsMonday() {
    assertEquals(
      SpeedWindow(slowLane = false, until = local("2026-10-05T09:00")),
      scheduler.evaluate(listOf(workHours), local("2026-10-02T19:00"))
    )
  }

  @Test
  fun evaluate_overnightRuleBeforeMidnight_endsNextMorning() {
    assertEquals(
      SpeedWindow(slowLane = true, until = local("2026-10-01T06:00")),
      scheduler.evaluate(listOf(nights), local("2026-09-30T23:30"))
    )
  }

  @Test
  fun evaluate_overnightRuleAfterMidnight_windowFromPreviousDayStillOpen() {
    assertEquals(
      SpeedWindow(slowLane = true, until = local("2026-10-01T06:00")),
      scheduler.evaluate(listOf(nights), local("2026-10-01T01:00"))
    )
  }

  @Test
  fun evaluate_overnightRuleOnFriday_belongsToTheDayItStarts() {
    val fridayNight = SpeedRule(days = setOf(Weekday.Friday), start = "22:00", end = "06:00")

    assertEquals(
      SpeedWindow(slowLane = true, until = local("2026-10-03T06:00")),
      scheduler.evaluate(listOf(fridayNight), local("2026-10-03T03:00"))
    )
    assertEquals(
      SpeedWindow(slowLane = false, until = local("2026-10-09T22:00")),
      scheduler.evaluate(listOf(fridayNight), local("2026-10-03T23:00"))
    )
  }

  @Test
  fun evaluate_springForwardNight_endKeepsLocalTime() {
    // Clocks skip from 02:00 to 03:00 on 2026-03-08, so the night lasts 7 hours.
    val window = scheduler.evaluate(listOf(nights), local("2026-03-08T05:30"))

    assertEquals(SpeedWindow(slowLane = true, until = local("2026-03-08T06:00")), window)
    assertEquals(Instant.parse("2026-03-08T10:00:00Z"), window.until)
    assertEquals(7.hours, Instant.parse("2026-03-08T10:00:00Z") - local("2026-03-07T22:00"))
  }

  @Test
  fun evaluate_startInSkippedHour_startsOnceClocksMoveOn() {
    val rule = SpeedRule(start = "02:30", end = "04:00")

    // 02:30 does not exist on 2026-03-08; the window opens at 03:30 daylight time.
    assertEquals(
      SpeedWindow(slowLane = false, until = Instant.parse("2026-03-08T07:30:00Z")),
      scheduler.evaluate(listOf(rule), Instant.parse("2026-03-08T07:00:00Z"))
    )
  }

  @Test
  fun evaluate_fallBackNight_endKeepsLocalTime() {
    // Clocks go back from 02:00 to 01:00 on 2026-11-01, so the night lasts 9 hours.
    val now = Instant.parse("2026-11-01T10:30:00Z")

    assertEquals(
      SpeedWindow(slowLane = true, until = Instant.parse("2026-11-01T11:00:00Z")),
      scheduler.evaluate(listOf(nights), now)
    )
    assertEquals(
      9.hours,
      Instant.parse("2026-11-01T11:00:00Z") - local("2026-10-31T22:00")
    )
  }

  @Test
  fun evaluate_adjacentRules_mergeIntoOneWindow() {
    val morning = SpeedRule(start = "09:00", end = "12:00")
    val afternoon = SpeedRule(start = "12:00", end = "18:00")

    assertEquals(
      SpeedWindow(slowLane = true, until = local("2026-09-30T18:00")),
      scheduler.evaluate(listOf(morning, afternoon), local("2026-09-30T10:00"))
    )
  }

  @Test
  fun evaluate_everyDayAllDay_slowLaneWithoutEnd() {
    val always = SpeedRule(start = "00:00", end = "00:00")

    assertEquals(
      SpeedWindow(slowLane = true, until = null),
      scheduler.evaluate(listOf(always), local("2026-09-30T10:00"))
    )
  }

  @Test
  fun evaluate_unreadableTime_ruleIgnored() {
    val broken = SpeedRule(start = "25:00", end = "06:00")

    assertEquals(
      SpeedWindow(slowLane = false, until = local("2026-09-30T22:00")),
      scheduler.evaluate(listOf(broken, nights), local("2026-09-30T10:00"))
    )
  }
}
