package com.linroid.ketch.app.ui.inspector.tabs

import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToLong
import kotlin.time.Duration
import kotlin.time.Instant

/** A size as compact as a column needs: "1.2 GB", "284 MB", "512 B". */
internal fun formatSize(bytes: Long): String {
  val unit = unitOf(bytes)
  return "${amount(bytes, unit.second)} ${unit.first}"
}

/** A speed as compact as a lane's column needs: "6.4 MB/s", "957 KB/s", "0 B/s". */
internal fun formatSpeed(bytesPerSecond: Long): String = "${formatSize(bytesPerSecond)}/s"

/**
 * [text] shortened in the middle with "…" to the longest form that [fits], keeping twice as much
 * of its end as of its start, where names keep their numbers and extensions.
 */
internal fun middleEllipsis(text: String, fits: (String) -> Boolean): String {
  if (fits(text)) return text
  fun kept(count: Int): String {
    val tail = count * 2 / 3
    return text.take(count - tail) + "…" + text.takeLast(tail)
  }
  var low = 0
  var high = text.length - 1
  while (low < high) {
    val mid = (low + high + 1) / 2
    if (fits(kept(mid))) low = mid else high = mid - 1
  }
  return kept(low)
}

/**
 * The bytes from [from] up to [to] in the unit of [to], such as "1.4–2.1 GB" or "640–960 MB":
 * one decimal below 100 of the unit, none above.
 */
internal fun formatByteRange(from: Long, to: Long): String {
  val unit = unitOf(to)
  return "${amount(from, unit.second)}–${amount(to, unit.second)} ${unit.first}"
}

/** [part] of [total] bytes in the unit of [total], such as "3.2 of 7.9 GB". */
internal fun formatBytesOf(part: Long, total: Long): String {
  val unit = unitOf(total)
  return "${amount(part, unit.second)} of ${amount(total, unit.second)} ${unit.first}"
}

/** How long a connection has received nothing: "Stalled 6 s", "Stalled 2 min". */
internal fun formatStall(stalledFor: Duration): String {
  val seconds = stalledFor.inWholeSeconds
  return if (seconds < 60) "Stalled $seconds s" else "Stalled ${seconds / 60} min"
}

/** "14:22", or "14:22:08" with [seconds], in [zone]. */
internal fun clockTime(at: Instant, zone: TimeZone, seconds: Boolean = false): String {
  val time = at.toLocalDateTime(zone)
  val minutes = "${time.hour.twoDigits()}:${time.minute.twoDigits()}"
  return if (seconds) "$minutes:${time.second.twoDigits()}" else minutes
}

/** "1 file", "14 files". */
internal fun plural(count: Int, one: String, many: String = "${one}s"): String =
  if (count == 1) "1 $one" else "$count $many"

/**
 * The largest unit [bytes] has at least one of, as its name and size; an amount that would round
 * to 1024 of it, such as 1023.6 KB, takes the next unit instead.
 */
private fun unitOf(bytes: Long): Pair<String, Long> {
  val index = ByteUnits.indexOfLast { bytes >= it.second }.coerceAtLeast(0)
  val next = ByteUnits.getOrNull(index + 1)
  val rounded = (bytes.toDouble() / ByteUnits[index].second).roundToLong()
  return if (next != null && rounded >= next.second / ByteUnits[index].second) {
    next
  } else {
    ByteUnits[index]
  }
}

private fun amount(bytes: Long, unit: Long): String {
  if (bytes == 0L || unit == 1L) return bytes.toString()
  val value = bytes.toDouble() / unit
  val tenths = (value * 10).roundToLong()
  // 99.96 rounds to 100.0, which reads as a whole number like any other amount above 100.
  if (tenths >= WHOLE_FROM_TENTHS) return value.roundToLong().toString()
  return "${tenths / 10}.${tenths % 10}"
}

private const val WHOLE_FROM_TENTHS = 1000L

private fun Int.twoDigits(): String = toString().padStart(2, '0')

private val ByteUnits = listOf(
  "B" to 1L,
  "KB" to (1L shl 10),
  "MB" to (1L shl 20),
  "GB" to (1L shl 30),
  "TB" to (1L shl 40)
)
