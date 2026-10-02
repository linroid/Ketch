package com.linroid.ketch.app.ui.pulse

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DiskSpace
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.formatSpace
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.util.formatBytes
import com.linroid.ketch.config.SpeedLimitMode
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * One part of the Pulse bar's counts, such as "3 waiting", which opens the tab of [filter].
 *
 * @property alert whether it counts failures, which it then shows in the failed color.
 * @property description what a screen reader says, such as "2 downloading" for "2↓".
 */
internal data class CountPart(
  val text: String,
  val filter: StatusFilter,
  val alert: Boolean = false,
  val description: String = text,
)

/**
 * The Pulse bar's counts, "2↓ · 3 waiting · 1 failed", as parts; zero counts are left out.
 * The Failed tab also lists canceled tasks, so its count is the tab's while the failed color
 * shows only for real [failures].
 */
internal fun countParts(counts: PulseCounts, failures: Int): List<CountPart> = buildList {
  if (counts.downloading > 0) {
    add(
      CountPart(
        text = "${counts.downloading}↓",
        filter = StatusFilter.Downloading,
        description = "${counts.downloading} downloading",
      )
    )
  }
  if (counts.waiting > 0) add(CountPart("${counts.waiting} waiting", StatusFilter.Waiting))
  if (counts.failed > 0) {
    add(CountPart("${counts.failed} failed", StatusFilter.Failed, alert = failures > 0))
  }
}

/** A speed split into its number and unit, "9.1" and "MB/s", which the bar styles apart. */
internal data class SpeedText(val amount: String, val unit: String) {
  override fun toString(): String = "$amount $unit"
}

/** [bytesPerSecond] as "9.1 MB/s". */
internal fun speedText(bytesPerSecond: Long): SpeedText {
  val bytes = formatBytes(bytesPerSecond.coerceAtLeast(0))
  return SpeedText(bytes.substringBefore(' '), "${bytes.substringAfter(' ')}/s")
}

/** Free space as the Pulse bar shows it: "412 GB free". */
internal fun diskLabel(disk: DiskSpace): String = "${formatSpace(disk.usableBytes)} free"

/** Share of the disk in use, from 0 to 1. */
internal fun diskUsed(disk: DiskSpace): Float {
  if (disk.totalBytes <= 0) return 0f
  return (1f - disk.usableBytes.toFloat() / disk.totalBytes).coerceIn(0f, 1f)
}

/**
 * How the Pulse bar names a device's connection: "Sharing :8642", "Not shared", "Live",
 * "Connecting", "Offline" or "Needs a token".
 */
internal fun healthLabel(health: DeviceHealth): String = when (health) {
  is DeviceHealth.Local -> health.sharingPort?.let { "Sharing :$it" } ?: "Not shared"
  DeviceHealth.Live -> "Live"
  DeviceHealth.Connecting -> "Connecting"
  is DeviceHealth.Offline -> "Offline"
  DeviceHealth.Unauthorized -> "Needs a token"
}

/**
 * Summary of the selected rows for the Pulse bar, "3 selected · 2.4 GB · 9.1 MB/s"; sizes and
 * speeds that are unknown or zero are left out. `null` when nothing listed is selected.
 */
internal fun selectionSummary(rows: List<TaskRow>, selected: Set<TaskKey>): String? {
  if (selected.isEmpty()) return null
  val chosen = rows.filter { it.key in selected }
  if (chosen.isEmpty()) return null
  val size = chosen.sumOf { it.sizeBytes ?: 0L }
  val speed = chosen.sumOf { it.speed ?: 0L }
  return listOfNotNull(
    "${chosen.size} selected",
    formatBytes(size).takeIf { size > 0 },
    speedText(speed).toString().takeIf { speed > 0 }
  ).joinToString(SEPARATOR)
}

/**
 * The limit downloads run at under [mode]: the slow lane's speed (never above the standing
 * cap) while it is in effect, otherwise [cap], the device's own limit.
 */
internal fun effectiveCap(
  mode: SpeedMode,
  cap: SpeedLimit,
  slowLane: SpeedLimit,
  standard: SpeedLimit,
): SpeedLimit = when {
  mode is SpeedMode.Auto && !mode.slowLane -> standard
  !mode.isSlowLane -> cap
  standard.isUnlimited || slowLane.bytesPerSecond < standard.bytesPerSecond -> slowLane
  else -> standard
}

/**
 * Label of the speed mode pill: "Full speed", "Capped · 20 MB/s", "Slow lane · 1 MB/s" or
 * "Auto · Slow lane until 18:00", with clock times local to [timeZone].
 *
 * @param limit the limit in effect, from [effectiveCap].
 */
internal fun speedModeLabel(
  mode: SpeedMode,
  limit: SpeedLimit,
  now: Instant,
  timeZone: TimeZone,
): String = when (mode) {
  SpeedMode.Full -> if (limit.isUnlimited) "Full speed" else "Capped · ${formatSpeedLimit(limit)}"
  SpeedMode.SlowLane -> "Slow lane · ${formatSpeedLimit(limit)}"
  is SpeedMode.Auto -> {
    val phase = if (mode.slowLane) "Slow lane" else "Full speed"
    val until = mode.until?.let { " until ${clockLabel(it, now, timeZone)}" }.orEmpty()
    "Auto · $phase$until"
  }
}

/** Name of [mode] in sentence case, as the popover and messages show it. */
internal fun speedModeName(mode: SpeedLimitMode): String = when (mode) {
  SpeedLimitMode.Full -> "Full speed"
  SpeedLimitMode.SlowLane -> "Slow lane"
  SpeedLimitMode.Auto -> "Auto"
}

/** "14:32" on the day of [now], "Tue 09:00" on another day; rounded to the nearest minute. */
internal fun clockLabel(instant: Instant, now: Instant, timeZone: TimeZone): String {
  val time = (instant + 30.seconds).toLocalDateTime(timeZone)
  val clock = "${time.hour.toString().padStart(2, '0')}:" +
    time.minute.toString().padStart(2, '0')
  if (time.date == now.toLocalDateTime(timeZone).date) return clock
  val day = time.dayOfWeek.name.take(3).lowercase().replaceFirstChar { it.uppercase() }
  return "$day $clock"
}

internal const val SEPARATOR = " · "
