package com.linroid.ketch.app.ui.pulse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchSpeedChart
import com.linroid.ketch.app.components.SpeedBand
import com.linroid.ketch.app.components.SpeedLimitLine
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DevicePulse
import com.linroid.ketch.app.state.SpeedHistory
import com.linroid.ketch.app.state.SpeedHistoryStore
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText
import kotlinx.datetime.TimeZone
import kotlinx.datetime.toLocalDateTime
import kotlin.math.roundToInt
import kotlin.time.Instant

/**
 * The speed popover of the Pulse bar, 320 dp wide: the active device's total speed over the last
 * five minutes, with its speed limit as a dashed line, and a row per device.
 */
@Composable
internal fun SpeedHistoryPopover(
  state: AppState,
  expanded: Boolean,
  onDismissRequest: () -> Unit,
) {
  PulsePopover(
    expanded = expanded,
    onDismissRequest = onDismissRequest,
    width = PopoverWidth,
    title = "Speed",
  ) {
    val pulse by state.pulse.state.collectAsState()
    val instances by state.instances.collectAsState()
    val histories by state.speedHistory.histories.collectAsState()
    val view = rememberSpeedModeView(state)
    val chart = remember(histories) { totalHistory(histories.values) }
    SpeedHistoryContent(
      samples = chart.samples,
      end = chart.end,
      limitLabel = view.label.takeUnless { view.limit.isUnlimited },
      limit = view.limit.takeUnless { it.isUnlimited }?.bytesPerSecond,
      devices = pulse.devices,
      pennantName = { device ->
        instances.firstOrNull { it.deviceId == device.deviceId }?.label ?: device.name
      },
    )
  }
}

/**
 * The body of the [SpeedHistoryPopover], drawn from its values.
 *
 * @param pennantName what a device's pennant monogram is made from: the host name of the
 *   embedded device rather than "This Mac".
 */
@Composable
internal fun SpeedHistoryContent(
  samples: List<Long>,
  end: Instant?,
  limitLabel: String?,
  limit: Long?,
  devices: List<DevicePulse>,
  pennantName: (DevicePulse) -> String = { it.name },
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val zone = remember { TimeZone.currentSystemDefault() }
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
    Text(
      text = eyebrowText("Last 5 minutes"),
      style = KetchTheme.typography.eyebrow,
      color = colors.textTertiary,
    )
    if (samples.any { it > 0 }) {
      KetchSpeedChart(
        bands = listOf(SpeedBand(samples, colors.accent)),
        limits = listOfNotNull(limit?.let { SpeedLimitLine(it, limitLabel) }),
        slots = SpeedHistoryStore.CAPACITY,
        timeLabel = { index ->
          val at = end?.minus(SpeedHistoryStore.INTERVAL * (SpeedHistoryStore.CAPACITY - 1 - index))
          at?.let { secondsLabel(it, zone) }.orEmpty()
        },
        modifier = Modifier.fillMaxWidth().height(ChartHeight),
      )
    } else {
      Text(
        text = "Nothing downloaded in the last 5 minutes.",
        style = KetchTheme.typography.caption,
        color = colors.textSecondary,
      )
    }
    Spacer(Modifier.height(spacing.s1))
    devices.forEach { device -> DeviceSpeedRow(device, pennantName(device)) }
  }
}

@Composable
private fun DeviceSpeedRow(device: DevicePulse, pennantName: String) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier.fillMaxWidth(),
  ) {
    DevicePennant(
      deviceId = device.deviceId,
      name = pennantName,
      size = DevicePennantDefaults.XSmall,
    )
    Column(Modifier.weight(1f)) {
      Text(
        text = device.name,
        style = KetchTheme.typography.label,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = if (device.cap.isUnlimited) "No limit" else "Limit ${formatSpeedLimit(device.cap)}",
        style = KetchTheme.typography.caption,
        color = colors.textTertiary,
        maxLines = 1,
      )
    }
    Text(
      text = if (device.health.isOnline) speedText(device.speed).toString() else "—",
      style = KetchTheme.typography.numeral,
      color = colors.textPrimary,
    )
  }
}

/** Total speed of every task once a second, as the speed popover charts it. */
internal class TotalHistory(val samples: List<Long>, val end: Instant?)

/**
 * Sums the speed [histories] of tasks second by second over the last
 * [SpeedHistoryStore.CAPACITY] seconds, ending at the newest sample. A task that was not
 * downloading at a second adds nothing to it.
 */
internal fun totalHistory(histories: Collection<SpeedHistory>): TotalHistory {
  val end = histories.maxOfOrNull { it.lastAt } ?: return TotalHistory(emptyList(), null)
  val slots = SpeedHistoryStore.CAPACITY
  val totals = LongArray(slots)
  for (history in histories) {
    // How many seconds before the end this history's newest sample was taken.
    val lag = ((end - history.lastAt) / SpeedHistoryStore.INTERVAL).roundToInt()
    for (index in 0 until history.size) {
      val slot = slots - 1 - lag - (history.size - 1 - index)
      if (slot in 0 until slots) totals[slot] += history[index].coerceAtLeast(0)
    }
  }
  return TotalHistory(totals.toList(), end)
}

private fun secondsLabel(instant: Instant, zone: TimeZone): String {
  val time = instant.toLocalDateTime(zone)
  return listOf(time.hour, time.minute, time.second)
    .joinToString(":") { it.toString().padStart(2, '0') }
}

private val PopoverWidth = 320.dp
private val ChartHeight = 96.dp
