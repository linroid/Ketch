package com.linroid.ketch.app.ui.inspector.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchDot
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.KetchSpeedChart
import com.linroid.ketch.app.components.SpeedBand
import com.linroid.ketch.app.components.SpeedLimitLine
import com.linroid.ketch.app.components.StatusDotDefaults
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.SpeedHistory
import com.linroid.ketch.app.state.SpeedHistoryStore
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.TimelineEntry
import com.linroid.ketch.app.state.TimelineKind
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.state.speedLimitText
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.clockTime
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.inspector_activity_empty
import ketch.app.shared.generated.resources.inspector_activity_now
import ketch.app.shared.generated.resources.inspector_activity_show_earlier
import ketch.app.shared.generated.resources.inspector_activity_since
import ketch.app.shared.generated.resources.inspector_global_slow_lane
import ketch.app.shared.generated.resources.inspector_limit_global
import ketch.app.shared.generated.resources.inspector_limit_marker
import ketch.app.shared.generated.resources.inspector_limit_task
import ketch.app.shared.generated.resources.inspector_stat_average
import ketch.app.shared.generated.resources.inspector_stat_connections
import ketch.app.shared.generated.resources.inspector_stat_peak
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The Activity tab of the inspector: the task's speed over the last five minutes and what
 * happened to it since the app opened.
 *
 * The 120 dp chart stacks one band per connection, in the lane colors of the Connections tab,
 * with dashed lines at the task's own limit and at the device's global limit (or Slow lane).
 * Hovering it shows the time and speed of a second. Peak, average and connections sit under it,
 * then the session timeline: added, started, paused, resumed, changes of connections, limit and
 * priority, failures and completion. Both come from [SpeedHistoryStore]; persistent history
 * needs the engine (W6).
 */
@Composable
fun ActivityTab(state: AppState, row: TaskRow, modifier: Modifier = Modifier) {
  val histories by state.speedHistory.histories.collectAsState()
  val timelines by state.speedHistory.timelines.collectAsState()
  val pulse by state.pulse.state.collectAsState()
  val device = pulse.devices.firstOrNull { it.deviceId == row.key.deviceId }
  val slowLane = row.key.deviceId == LOCAL_DEVICE_ID && pulse.mode.isSlowLane
  key(row.key) {
    ActivityTabContent(
      row = row,
      history = histories[row.key],
      timeline = timelines[row.key].orEmpty(),
      modifier = modifier,
      globalLimit = device?.cap ?: SpeedLimit.Unlimited,
      globalLabel = if (slowLane) {
        Res.string.inspector_global_slow_lane.text()
      } else {
        Res.string.inspector_limit_global.text()
      },
    )
  }
}

/**
 * [ActivityTab] for [row] with its speed [history] and [timeline], without the app state. A
 * place that shows different tasks in turn keys it by task.
 *
 * @param globalLimit the cap of the task's device, drawn as a second dashed line.
 * @param globalLabel what that cap is called on its line.
 */
@Composable
internal fun ActivityTabContent(
  row: TaskRow,
  history: SpeedHistory?,
  timeline: List<TimelineEntry>,
  modifier: Modifier = Modifier,
  globalLimit: SpeedLimit = SpeedLimit.Unlimited,
  globalLabel: UiText = Res.string.inspector_limit_global.text(),
  timeZone: TimeZone = remember { TimeZone.currentSystemDefault() },
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(spacing.s3),
  ) {
    if (history == null && timeline.isEmpty()) {
      Text(
        text = stringResource(Res.string.inspector_activity_empty),
        style = type.caption,
        color = colors.textTertiary,
      )
    }
    if (history != null) {
      val bands = remember(history, colors) { activityBands(history, colors) }
      val limits = activityLimits(row.request.speedLimit, globalLimit, globalLabel)
        .map { SpeedLimitLine(it.bytesPerSecond, it.label?.resolve()) }
      val offset = SpeedHistoryStore.CAPACITY - history.size
      KetchSpeedChart(
        bands = bands,
        limits = limits,
        slots = SpeedHistoryStore.CAPACITY,
        timeLabel = { slot -> clockTime(history.timeAt(slot - offset), timeZone, seconds = true) },
        modifier = Modifier.fillMaxWidth().height(spacing.s10 * 3),
      )
      val downloading = row.state is DownloadState.Downloading
      Row(modifier = Modifier.fillMaxWidth()) {
        val first = history.timeAt(-offset)
        Text(
          text = clockTime(first, timeZone),
          style = type.numeralS,
          color = colors.textTertiary,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = if (downloading) {
            stringResource(Res.string.inspector_activity_now)
          } else {
            clockTime(history.lastAt, timeZone)
          },
          style = type.numeralS,
          color = colors.textTertiary,
        )
      }
      Stats(activityStats(history, row.connections))
    }
    if (timeline.isNotEmpty()) Timeline(timeline, timeZone)
  }
}

/** Named numbers side by side, sharing the width. */
@Composable
private fun Stats(stats: List<Pair<UiText, UiText>>) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  Row(modifier = Modifier.fillMaxWidth()) {
    for ((name, value) in stats) {
      Column(
        verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s0_5),
        modifier = Modifier.weight(1f),
      ) {
        Text(
          text = name.resolve(),
          style = type.caption,
          color = colors.textTertiary,
          maxLines = 1,
        )
        Text(
          text = value.resolve(),
          style = type.numeral,
          color = colors.textPrimary,
          maxLines = 1,
        )
      }
    }
  }
}

/** The session timeline, the newest entries last; older ones fold behind a button. */
@Composable
private fun Timeline(entries: List<TimelineEntry>, timeZone: TimeZone) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  var expanded by remember { mutableStateOf(false) }
  val hidden = if (expanded) 0 else (entries.size - TIMELINE_SHOWN).coerceAtLeast(0)
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
    KetchEyebrow(stringResource(Res.string.inspector_activity_since))
    if (hidden > 0) {
      KetchButton(
        text = pluralStringResource(Res.plurals.inspector_activity_show_earlier, hidden, hidden),
        onClick = { expanded = true },
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
      )
    }
    for (entry in entries.drop(hidden)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      ) {
        Text(
          text = clockTime(entry.at, timeZone, seconds = true),
          style = type.numeralS,
          color = colors.textTertiary,
        )
        KetchDot(color = colors.timelineColor(entry.kind), size = StatusDotDefaults.TableSize)
        Text(
          text = entry.label.resolve(),
          style = type.bodyS,
          color = colors.textPrimary,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
  }
}

/**
 * The chart's bands: one per connection in the lane colors when [history] split every second's
 * speed between two or more of them; otherwise the task's speed in the accent, as for a torrent
 * or seconds recorded without connections.
 */
internal fun activityBands(history: SpeedHistory, colors: KetchColors): List<SpeedBand> {
  val lanes = history.lanes
  val split = lanes.size >= 2 && (0 until history.size).all { i ->
    lanes.sumOf { it[i] } == history[i]
  }
  if (!split) return listOf(SpeedBand(history.toList(), colors.accent))
  return lanes.mapIndexed { index, lane ->
    SpeedBand(lane.toList(), colors.lanes[index % colors.lanes.size])
  }
}

/** A dashed line of the chart at [bytesPerSecond], with its [label] when there is room. */
internal data class LimitLine(val bytesPerSecond: Long, val label: UiText? = null)

/**
 * Dashed lines at the [task]'s own limit and at the [global] one, called [globalLabel]. A global
 * limit equal to the task's is drawn once, and of two limits too close for both labels only the
 * lower one, which applies, keeps its label.
 */
internal fun activityLimits(
  task: SpeedLimit,
  global: SpeedLimit,
  globalLabel: UiText,
): List<LimitLine> {
  val lines = buildList {
    if (!task.isUnlimited) {
      add(task.bytesPerSecond to Res.string.inspector_limit_task.text(speedLimitText(task)))
    }
    if (!global.isUnlimited && global != task) {
      val label = Res.string.inspector_limit_marker.text(globalLabel, speedLimitText(global))
      add(global.bytesPerSecond to label)
    }
  }.sortedBy { it.first }
  val close = lines.size == 2 && lines[1].first < lines[0].first * CLOSE_LIMITS
  return lines.mapIndexed { index, (bytes, label) ->
    LimitLine(bytes, label.takeUnless { close && index == 1 })
  }
}

/**
 * Peak, average and connections under the chart: "31.2 MB/s", "14.0 MB/s" and "8", each with
 * its name; connections only for a task that has them.
 */
internal fun activityStats(history: SpeedHistory, connections: Int?): List<Pair<UiText, UiText>> =
  buildList {
    add(Res.string.inspector_stat_peak.text() to compactSpeedText(history.peak))
    add(Res.string.inspector_stat_average.text() to compactSpeedText(history.average))
    if (connections != null && connections > 0) {
      add(Res.string.inspector_stat_connections.text() to verbatim(connections.toString()))
    }
  }

/** The dot of a timeline entry, in the color of the state it records. */
internal fun KetchColors.timelineColor(kind: TimelineKind): Color = when (kind) {
  TimelineKind.Added, TimelineKind.Changed -> textTertiary
  TimelineKind.Started, TimelineKind.Resumed -> status.downloading.color
  TimelineKind.Paused -> status.paused.color
  TimelineKind.Queued -> status.queued.color
  TimelineKind.Scheduled -> status.scheduled.color
  TimelineKind.Failed -> status.failed.color
  TimelineKind.Completed -> status.completed.color
  TimelineKind.Canceled -> status.canceled.color
}

private const val TIMELINE_SHOWN = 12

/** Two limits closer than this ratio share one label. */
private const val CLOSE_LIMITS = 1.5
