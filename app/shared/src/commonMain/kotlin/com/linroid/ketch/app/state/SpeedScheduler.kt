package com.linroid.ketch.app.state

import com.linroid.ketch.config.SpeedLimitMode
import com.linroid.ketch.config.SpeedRule
import com.linroid.ketch.config.Weekday
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.atTime
import kotlinx.datetime.plus
import kotlinx.datetime.toInstant
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.days
import kotlin.time.Instant

/**
 * Whether the Auto speed rules have the slow lane on at some moment, and until when.
 *
 * @property slowLane whether a rule has the slow lane on.
 * @property until when that changes next; `null` when no rule changes it within a week.
 */
data class SpeedWindow(
  val slowLane: Boolean,
  val until: Instant?,
)

/**
 * Evaluates the weekly [SpeedRule]s of [SpeedLimitMode.Auto].
 *
 * Rule times are wall-clock times: a window keeps its local start and end across daylight saving
 * changes, and a time that falls in the hour skipped in spring is read as the time the clocks
 * show once they have moved on. An end equal to the start makes a window of a whole day.
 *
 * @param timeZone time zone the rules are read in. It is looked up on every evaluation, so a
 *   change of the system time zone applies at once.
 */
class SpeedScheduler(
  private val timeZone: () -> TimeZone = { TimeZone.currentSystemDefault() },
) {
  /** Evaluates [rules] at [now]. Rules with a start or end that is not `HH:MM` are ignored. */
  fun evaluate(rules: List<SpeedRule>, now: Instant): SpeedWindow {
    val zone = timeZone()
    val today = now.toLocalDateTime(zone).date
    // A window lasts at most a day, so yesterday's may still be open, and a weekly rule
    // starts again within the next seven days.
    val days = (-1..7).map { today.plus(it, DateTimeUnit.DAY) }
    val windows = rules.mapNotNull { it.times() }
      .flatMap { rule -> days.mapNotNull { day -> rule.windowOn(day, zone) } }
      .sortedBy { it.start }
    val merged = ArrayList<OpenEndRange<Instant>>()
    for (window in windows) {
      val last = merged.lastOrNull()
      if (last != null && window.start <= last.endExclusive) {
        merged[merged.lastIndex] = last.start..<maxOf(last.endExclusive, window.endExclusive)
      } else {
        merged += window
      }
    }
    val current = merged.firstOrNull { now in it }
    if (current != null) {
      // Windows that run into the edge of the evaluated week cover every day.
      val until = current.endExclusive.takeIf { it - now <= HORIZON }
      return SpeedWindow(slowLane = true, until = until)
    }
    return SpeedWindow(slowLane = false, until = merged.firstOrNull { it.start > now }?.start)
  }

  private class RuleTimes(
    val days: Set<Weekday>,
    val start: LocalTime,
    val end: LocalTime,
  ) {
    fun windowOn(day: LocalDate, zone: TimeZone): OpenEndRange<Instant>? {
      if (days.isNotEmpty() && Weekday.entries[day.dayOfWeek.ordinal] !in days) return null
      val endDay = if (end > start) day else day.plus(1, DateTimeUnit.DAY)
      val startAt = day.atTime(start).toInstant(zone)
      val endAt = endDay.atTime(end).toInstant(zone)
      return if (endAt > startAt) startAt..<endAt else null
    }
  }

  private fun SpeedRule.times(): RuleTimes? {
    val startTime = parseTime(start) ?: return null
    val endTime = parseTime(end) ?: return null
    return RuleTimes(days, startTime, endTime)
  }

  private fun parseTime(value: String): LocalTime? {
    val parts = value.trim().split(':')
    if (parts.size != 2) return null
    val hour = parts[0].toIntOrNull() ?: return null
    val minute = parts[1].toIntOrNull() ?: return null
    if (hour !in 0..23 || minute !in 0..59) return null
    return LocalTime(hour, minute)
  }

  private companion object {
    val HORIZON = 7.days
  }
}
