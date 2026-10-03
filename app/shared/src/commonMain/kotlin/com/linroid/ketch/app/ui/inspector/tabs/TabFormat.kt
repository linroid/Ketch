package com.linroid.ketch.app.ui.inspector.tabs

import com.linroid.ketch.app.i18n.ByteUnit
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.decimalSeparator
import com.linroid.ketch.app.i18n.text
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.inspector_stalled_minutes
import ketch.app.shared.generated.resources.inspector_stalled_seconds
import ketch.app.shared.generated.resources.size_progress
import ketch.app.shared.generated.resources.speed_per_second
import kotlin.math.roundToLong
import kotlin.time.Duration

/** A size as compact as a column needs: "1.2 GB", "284 MB", "512 B". */
internal fun compactSizeText(bytes: Long): UiText {
  val unit = byteUnitOf(bytes)
  return unit.text(localAmount(bytes, unit))
}

/** A speed as compact as a lane's column needs: "6.4 MB/s", "957 KB/s", "0 B/s". */
internal fun compactSpeedText(bytesPerSecond: Long): UiText =
  Res.string.speed_per_second.text(compactSizeText(bytesPerSecond))

/**
 * The bytes from [from] up to [to] in the unit of [to], such as "1.4–2.1 GB" or "640–960 MB":
 * one decimal below 100 of the unit, none above.
 */
internal fun byteRangeText(from: Long, to: Long): UiText {
  val unit = byteUnitOf(to)
  return unit.text("${localAmount(from, unit)}–${localAmount(to, unit)}")
}

/** [part] of [total] bytes in the unit of [total], such as "3.2 of 7.9 GB". */
internal fun bytesOfText(part: Long, total: Long): UiText {
  val unit = byteUnitOf(total)
  return Res.string.size_progress.text(localAmount(part, unit), unit.text(localAmount(total, unit)))
}

/** Whether [a] and [b] bytes read in the same unit, as "2.4" and "5.7 GB" do. */
internal fun sameByteUnit(a: Long, b: Long): Boolean = byteUnitOf(a) == byteUnitOf(b)

/** How long a connection has received nothing: "Stalled 6 s", "Stalled 2 min". */
internal fun stallText(stalledFor: Duration): UiText {
  val seconds = stalledFor.inWholeSeconds
  return if (seconds < 60) {
    Res.string.inspector_stalled_seconds.text(seconds)
  } else {
    Res.string.inspector_stalled_minutes.text(seconds / 60)
  }
}

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
 * The largest unit [bytes] has at least one of; an amount that would round to 1024 of it, such
 * as 1023.6 KB, takes the next unit instead.
 */
private fun byteUnitOf(bytes: Long): ByteUnit {
  val units = ByteUnit.entries
  val index = units.indexOfLast { bytes >= it.bytes }.coerceAtLeast(0)
  val unit = units[index]
  val next = units.getOrNull(index + 1) ?: return unit
  val rounded = (bytes.toDouble() / unit.bytes).roundToLong()
  return if (rounded >= next.bytes / unit.bytes) next else unit
}

/** [bytes] in [unit], with the decimal separator of the locale: "1.4", or "1,4" in German. */
private fun localAmount(bytes: Long, unit: ByteUnit): String {
  if (bytes == 0L || unit == ByteUnit.B) return bytes.toString()
  val value = bytes.toDouble() / unit.bytes
  val tenths = (value * 10).roundToLong()
  // 99.96 rounds to 100.0, which reads as a whole number like any other amount above 100.
  if (tenths >= WHOLE_FROM_TENTHS) return value.roundToLong().toString()
  return "${tenths / 10}${decimalSeparator()}${tenths % 10}"
}

private const val WHOLE_FROM_TENTHS = 1000L
