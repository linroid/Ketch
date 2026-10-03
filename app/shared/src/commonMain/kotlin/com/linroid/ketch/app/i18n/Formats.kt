package com.linroid.ketch.app.i18n

import com.linroid.ketch.api.DownloadPriority
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.date_month_day
import ketch.app.shared.generated.resources.date_month_day_year
import ketch.app.shared.generated.resources.duration_days
import ketch.app.shared.generated.resources.duration_days_hours
import ketch.app.shared.generated.resources.duration_hours
import ketch.app.shared.generated.resources.duration_hours_minutes
import ketch.app.shared.generated.resources.duration_minutes
import ketch.app.shared.generated.resources.duration_minutes_seconds
import ketch.app.shared.generated.resources.duration_seconds
import ketch.app.shared.generated.resources.duration_under_second
import ketch.app.shared.generated.resources.month_apr
import ketch.app.shared.generated.resources.month_aug
import ketch.app.shared.generated.resources.month_dec
import ketch.app.shared.generated.resources.month_feb
import ketch.app.shared.generated.resources.month_jan
import ketch.app.shared.generated.resources.month_jul
import ketch.app.shared.generated.resources.month_jun
import ketch.app.shared.generated.resources.month_mar
import ketch.app.shared.generated.resources.month_may
import ketch.app.shared.generated.resources.month_nov
import ketch.app.shared.generated.resources.month_oct
import ketch.app.shared.generated.resources.month_sep
import ketch.app.shared.generated.resources.percent
import ketch.app.shared.generated.resources.priority_high
import ketch.app.shared.generated.resources.priority_low
import ketch.app.shared.generated.resources.priority_normal
import ketch.app.shared.generated.resources.priority_urgent
import ketch.app.shared.generated.resources.size_bytes
import ketch.app.shared.generated.resources.size_gb
import ketch.app.shared.generated.resources.size_kb
import ketch.app.shared.generated.resources.size_mb
import ketch.app.shared.generated.resources.size_tb
import ketch.app.shared.generated.resources.speed_per_second
import ketch.app.shared.generated.resources.weekday_fri
import ketch.app.shared.generated.resources.weekday_mon
import ketch.app.shared.generated.resources.weekday_sat
import ketch.app.shared.generated.resources.weekday_sun
import ketch.app.shared.generated.resources.weekday_thu
import ketch.app.shared.generated.resources.weekday_tue
import ketch.app.shared.generated.resources.weekday_wed
import kotlinx.datetime.DayOfWeek
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.LocalTime
import kotlinx.datetime.Month
import org.jetbrains.compose.resources.StringResource
import kotlin.time.Duration

/** A unit of data sizes, with the size of one of it in [bytes]. */
enum class ByteUnit(val bytes: Long, private val resource: StringResource) {
  B(1L, Res.string.size_bytes),
  KB(1L shl 10, Res.string.size_kb),
  MB(1L shl 20, Res.string.size_mb),
  GB(1L shl 30, Res.string.size_gb),
  TB(1L shl 40, Res.string.size_tb);

  /** [amount], an already formatted number, in this unit: "1.5 MB". */
  fun text(amount: String): UiText = resource.text(amount)

  companion object {
    /** The largest unit [bytes] has at least one of; [B] below one kilobyte. */
    fun of(bytes: Long): ByteUnit = entries.lastOrNull { bytes >= it.bytes } ?: B
  }
}

/**
 * [bytes] in the largest unit it has at least one of: whole bytes, kilo- and megabytes to one
 * decimal and gigabytes to two, as in "512 B", "1.5 MB" and "2.41 GB". Negative sizes, which mean
 * unknown, read "--".
 */
fun sizeText(bytes: Long): UiText {
  if (bytes < 0) return verbatim("--")
  val unit = ByteUnit.of(bytes).coerceAtMost(ByteUnit.GB)
  val decimals = when (unit) {
    ByteUnit.B -> 0
    ByteUnit.KB, ByteUnit.MB -> 1
    else -> 2
  }
  return unit.text(decimal(bytes.toDouble() / unit.bytes, decimals))
}

/** [bytesPerSecond] as a speed: "1.5 MB/s". */
fun speedText(bytesPerSecond: Long): UiText =
  Res.string.speed_per_second.text(sizeText(bytesPerSecond.coerceAtLeast(0)))

/**
 * [value] rounded half up to [decimals] places, with the decimal separator of the current
 * locale: "1.5", or "1,5" in German.
 */
fun decimal(value: Double, decimals: Int): String {
  var scale = 1L
  repeat(decimals) { scale *= 10 }
  val scaled = (value * scale + 0.5).toLong()
  if (decimals == 0) return scaled.toString()
  val fraction = (scaled % scale).toString().padStart(decimals, '0')
  return "${scaled / scale}${decimalSeparator()}$fraction"
}

/** [value] percent: "40%", or "40 %" in French. */
fun percentText(value: Int): UiText = Res.string.percent.text(value)

/**
 * [seconds] to the second, in at most two units: "1h 5m", "4m 2s", "9s". Nothing for zero or
 * less.
 */
fun etaText(seconds: Long): UiText {
  if (seconds <= 0) return UiText.Empty
  val h = seconds / 3600
  val m = (seconds % 3600) / 60
  val s = seconds % 60
  return when {
    h > 0 -> Res.string.duration_hours_minutes.text(h, m)
    m > 0 -> Res.string.duration_minutes_seconds.text(m, s)
    else -> Res.string.duration_seconds.text(s)
  }
}

/** [duration] like [etaText], with anything shorter than a second as "<1s". */
fun durationText(duration: Duration): UiText {
  val seconds = duration.inWholeSeconds
  return if (seconds <= 0) Res.string.duration_under_second.text() else etaText(seconds)
}

/**
 * [duration] to the minute, rounded up so a countdown never reads zero early: "45s", "30 min",
 * "3h 12m", "2d 4h".
 */
fun spanText(duration: Duration): UiText {
  val seconds = duration.inWholeSeconds.coerceAtLeast(0)
  if (seconds < 60) return Res.string.duration_seconds.text(seconds)
  val minutes = (seconds + 59) / 60
  val days = minutes / (24 * 60)
  val hours = minutes / 60 % 24
  val mins = minutes % 60
  return when {
    days > 0 && hours > 0 -> Res.string.duration_days_hours.text(days, hours)
    days > 0 -> Res.string.duration_days.text(days)
    hours > 0 && mins > 0 -> Res.string.duration_hours_minutes.text(hours, mins)
    hours > 0 -> Res.string.duration_hours.text(hours)
    else -> Res.string.duration_minutes.text(mins)
  }
}

/** The name of [priority]: "Low", "Normal", "High" or "Urgent". */
fun priorityText(priority: DownloadPriority): UiText = when (priority) {
  DownloadPriority.LOW -> Res.string.priority_low.text()
  DownloadPriority.NORMAL -> Res.string.priority_normal.text()
  DownloadPriority.HIGH -> Res.string.priority_high.text()
  DownloadPriority.URGENT -> Res.string.priority_urgent.text()
}

/** The short name of [month]: "Sep". */
fun monthShortText(month: Month): UiText = MONTHS[month.ordinal].text()

/** The short name of [day]: "Mon". */
fun weekdayShortText(day: DayOfWeek): UiText = WEEKDAYS[day.ordinal].text()

/** [date] without its weekday: "Sep 28", and "Sep 28, 2025" when it is not in [today]'s year. */
fun shortDateText(date: LocalDate, today: LocalDate): UiText {
  val month = monthShortText(date.month)
  return if (date.year == today.year) {
    Res.string.date_month_day.text(month, date.month.ordinal + 1, date.day)
  } else {
    Res.string.date_month_day_year.text(month, date.month.ordinal + 1, date.day, date.year)
  }
}

/** The hour and minute of [time] on a 24-hour clock: "09:05". */
fun clockTime(time: LocalDateTime): String = clockTime(time.time)

/** The hour and minute of [time] on a 24-hour clock: "09:05". */
fun clockTime(time: LocalTime): String =
  "${time.hour.toString().padStart(2, '0')}:${time.minute.toString().padStart(2, '0')}"

private val MONTHS = listOf(
  Res.string.month_jan,
  Res.string.month_feb,
  Res.string.month_mar,
  Res.string.month_apr,
  Res.string.month_may,
  Res.string.month_jun,
  Res.string.month_jul,
  Res.string.month_aug,
  Res.string.month_sep,
  Res.string.month_oct,
  Res.string.month_nov,
  Res.string.month_dec,
)

private val WEEKDAYS = listOf(
  Res.string.weekday_mon,
  Res.string.weekday_tue,
  Res.string.weekday_wed,
  Res.string.weekday_thu,
  Res.string.weekday_fri,
  Res.string.weekday_sat,
  Res.string.weekday_sun,
)
