package com.linroid.ketch.app.ui.pulse

import androidx.compose.runtime.Composable
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.speedText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DiskSpace
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.clockLabel
import com.linroid.ketch.app.state.formatSpace
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.state.speedLimitText
import com.linroid.ketch.config.SpeedLimitMode
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.device_free_space
import ketch.app.shared.generated.resources.pulse_count_downloading
import ketch.app.shared.generated.resources.pulse_count_failed
import ketch.app.shared.generated.resources.pulse_count_waiting
import ketch.app.shared.generated.resources.pulse_full_speed_until
import ketch.app.shared.generated.resources.pulse_health_connecting
import ketch.app.shared.generated.resources.pulse_health_live
import ketch.app.shared.generated.resources.pulse_health_needs_token
import ketch.app.shared.generated.resources.pulse_health_not_shared
import ketch.app.shared.generated.resources.pulse_health_offline
import ketch.app.shared.generated.resources.pulse_health_sharing
import ketch.app.shared.generated.resources.pulse_mode_auto
import ketch.app.shared.generated.resources.pulse_mode_auto_name
import ketch.app.shared.generated.resources.pulse_mode_capped
import ketch.app.shared.generated.resources.pulse_mode_full
import ketch.app.shared.generated.resources.pulse_mode_slow_lane
import ketch.app.shared.generated.resources.pulse_selected
import ketch.app.shared.generated.resources.pulse_slow_lane
import ketch.app.shared.generated.resources.pulse_slow_lane_until
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/**
 * One part of the Pulse bar's counts, such as "3 waiting", which opens the tab of [filter].
 *
 * @property alert whether it counts failures, which it then shows in the failed color.
 * @property description what a screen reader says, such as "2 downloading" for "2↓".
 */
internal data class CountPart(
  val text: UiText,
  val filter: StatusFilter,
  val alert: Boolean = false,
  val description: UiText = text,
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
        text = verbatim("${counts.downloading}↓"),
        filter = StatusFilter.Downloading,
        description = Res.plurals.pulse_count_downloading.text(counts.downloading),
      )
    )
  }
  if (counts.waiting > 0) {
    add(CountPart(Res.plurals.pulse_count_waiting.text(counts.waiting), StatusFilter.Waiting))
  }
  if (counts.failed > 0) {
    val text = Res.plurals.pulse_count_failed.text(counts.failed)
    add(CountPart(text, StatusFilter.Failed, alert = failures > 0))
  }
}

/** A speed split into its number and unit, "9.1" and "MB/s", which the bar styles apart. */
internal data class SpeedText(val amount: String, val unit: String) {
  override fun toString(): String = "$amount $unit"
}

/** [bytesPerSecond] as "9.1 MB/s" in the language of the composition, split by [splitSpeed]. */
@Composable
internal fun speedParts(bytesPerSecond: Long): SpeedText =
  splitSpeed(speedText(bytesPerSecond).resolve())

/**
 * [speed], such as "9.1 MB/s", split at its first space into "9.1" and "MB/s"; a speed written
 * without a space is all amount.
 */
internal fun splitSpeed(speed: String): SpeedText {
  val space = speed.indexOfFirst { it.isWhitespace() }
  if (space < 0) return SpeedText(speed, "")
  return SpeedText(speed.substring(0, space), speed.substring(space + 1).trim())
}

/** Free space as the Pulse bar shows it: "412 GB free". */
internal fun diskLabel(disk: DiskSpace): UiText =
  Res.string.device_free_space.text(formatSpace(disk.usableBytes))

/** Share of the disk in use, from 0 to 1. */
internal fun diskUsed(disk: DiskSpace): Float {
  if (disk.totalBytes <= 0) return 0f
  return (1f - disk.usableBytes.toFloat() / disk.totalBytes).coerceIn(0f, 1f)
}

/**
 * How the Pulse bar names a device's connection: "Sharing :8642", "Not shared", "Live",
 * "Connecting", "Offline" or "Needs a token".
 */
internal fun healthText(health: DeviceHealth): UiText = when (health) {
  is DeviceHealth.Local -> health.sharingPort?.let { Res.string.pulse_health_sharing.text(it) }
    ?: Res.string.pulse_health_not_shared.text()
  DeviceHealth.Live -> Res.string.pulse_health_live.text()
  DeviceHealth.Connecting -> Res.string.pulse_health_connecting.text()
  is DeviceHealth.Offline -> Res.string.pulse_health_offline.text()
  DeviceHealth.Unauthorized -> Res.string.pulse_health_needs_token.text()
}

/**
 * Summary of the selected rows for the Pulse bar, "3 selected · 2.4 GB · 9.1 MB/s"; sizes and
 * speeds that are unknown or zero are left out. `null` when nothing listed is selected.
 */
internal fun selectionSummary(rows: List<TaskRow>, selected: Set<TaskKey>): UiText? {
  if (selected.isEmpty()) return null
  val chosen = rows.filter { it.key in selected }
  if (chosen.isEmpty()) return null
  val size = chosen.sumOf { it.sizeBytes ?: 0L }
  val speed = chosen.sumOf { it.speed ?: 0L }
  return listOfNotNull(
    Res.plurals.pulse_selected.text(chosen.size),
    sizeText(size).takeIf { size > 0 },
    speedText(speed).takeIf { speed > 0 }
  ).joinText()
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
internal fun speedModeLabelText(
  mode: SpeedMode,
  limit: SpeedLimit,
  now: Instant,
  timeZone: TimeZone,
): UiText = when (mode) {
  SpeedMode.Full -> if (limit.isUnlimited) {
    Res.string.pulse_mode_full.text()
  } else {
    Res.string.pulse_mode_capped.text(speedLimitText(limit))
  }
  SpeedMode.SlowLane -> Res.string.pulse_mode_slow_lane.text(speedLimitText(limit))
  is SpeedMode.Auto -> {
    val until = mode.until?.let { clockLabel(it, now, timeZone) }
    val phase = when {
      mode.slowLane && until != null -> Res.string.pulse_slow_lane_until.text(until)
      mode.slowLane -> Res.string.pulse_slow_lane.text()
      until != null -> Res.string.pulse_full_speed_until.text(until)
      else -> Res.string.pulse_mode_full.text()
    }
    Res.string.pulse_mode_auto.text(phase)
  }
}

/** Name of [mode] in sentence case, as the popover and messages show it. */
internal fun speedModeText(mode: SpeedLimitMode): UiText = when (mode) {
  SpeedLimitMode.Full -> Res.string.pulse_mode_full.text()
  SpeedLimitMode.SlowLane -> Res.string.pulse_slow_lane.text()
  SpeedLimitMode.Auto -> Res.string.pulse_mode_auto_name.text()
}
