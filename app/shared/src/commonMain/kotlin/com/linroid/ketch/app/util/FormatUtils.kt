package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadPriority
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.Month
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

fun extractFilename(url: String): String {
  val path = url.trim()
    .substringBefore("?")
    .substringBefore("#")
    .trimEnd('/')
    .substringAfterLast("/")
  return path.ifBlank { "" }
}

fun priorityLabel(priority: DownloadPriority): String {
  return when (priority) {
    DownloadPriority.LOW -> "Low"
    DownloadPriority.NORMAL -> "Normal"
    DownloadPriority.HIGH -> "High"
    DownloadPriority.URGENT -> "Urgent"
  }
}

fun formatBytes(bytes: Long): String {
  if (bytes < 0) return "--"
  val kb = 1024L
  val mb = kb * 1024
  val gb = mb * 1024
  return when {
    bytes < kb -> "$bytes B"
    bytes < mb -> {
      val tenths = (bytes * 10 + kb / 2) / kb
      "${tenths / 10}.${tenths % 10} KB"
    }
    bytes < gb -> {
      val tenths = (bytes * 10 + mb / 2) / mb
      "${tenths / 10}.${tenths % 10} MB"
    }
    else -> {
      val hundredths = (bytes * 100 + gb / 2) / gb
      "${hundredths / 100}.${
        (hundredths % 100)
          .toString().padStart(2, '0')
      } GB"
    }
  }
}

fun formatEta(seconds: Long): String {
  if (seconds <= 0) return ""
  val h = seconds / 3600
  val m = (seconds % 3600) / 60
  val s = seconds % 60
  return when {
    h > 0 -> "${h}h ${m}m"
    m > 0 -> "${m}m ${s}s"
    else -> "${s}s"
  }
}

/** Formats [duration] like [formatEta], showing anything shorter than a second as "<1s". */
fun formatDuration(duration: Duration): String {
  return formatEta(duration.inWholeSeconds).ifEmpty { "<1s" }
}

/** Bytes per second over [duration], or `null` when it is too short to measure. */
fun averageSpeed(bytes: Long, duration: Duration): Long? {
  val millis = duration.inWholeMilliseconds
  return if (millis > 0) bytes * 1000 / millis else null
}

/** "1 download" or "3 downloads". */
internal fun downloads(count: Int): String = if (count == 1) "1 download" else "$count downloads"

/** "09:05": [hour] and [minute] on a 24-hour clock. */
internal fun clockText(hour: Int, minute: Int): String = "${hour.twoDigits()}:${minute.twoDigits()}"

/** "09:05", or "09:05:07" with [seconds]: this time of day on a 24-hour clock. */
internal fun LocalDateTime.clockText(seconds: Boolean = false): String {
  val minutes = clockText(hour, minute)
  return if (seconds) "$minutes:${second.twoDigits()}" else minutes
}

/** "14:22", or "14:22:08" with [seconds], in [zone]. */
internal fun clockTime(at: Instant, zone: TimeZone, seconds: Boolean = false): String =
  at.toLocalDateTime(zone).clockText(seconds)

/** "14:32" on the day of [now], "Tue 09:00" on another day; rounded to the nearest minute. */
internal fun clockLabel(instant: Instant, now: Instant, timeZone: TimeZone): String {
  val time = (instant + 30.seconds).toLocalDateTime(timeZone)
  val clock = time.clockText()
  if (time.date == now.toLocalDateTime(timeZone).date) return clock
  val day = time.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
  return "$day $clock"
}

/** "Jan" to "Dec". */
internal val Month.shortName: String get() = MonthNames[ordinal]

private fun Int.twoDigits(): String = toString().padStart(2, '0')

private val MonthNames = listOf(
  "Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"
)
