package com.linroid.ketch.app.util

import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Instant

fun extractFilename(url: String): String {
  val path = url.trim()
    .substringBefore("?")
    .substringBefore("#")
    .trimEnd('/')
    .substringAfterLast("/")
  return path.ifBlank { "" }
}

/** Bytes per second over [duration], or `null` when it is too short to measure. */
fun averageSpeed(bytes: Long, duration: Duration): Long? {
  val millis = duration.inWholeMilliseconds
  return if (millis > 0) bytes * 1000 / millis else null
}

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

private fun Int.twoDigits(): String = toString().padStart(2, '0')
