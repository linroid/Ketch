package com.linroid.ketch.app.ui.inspector.tabs

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.components.ConnectionStepper
import com.linroid.ketch.app.components.KetchDot
import com.linroid.ketch.app.components.LanePhase
import com.linroid.ketch.app.components.LaneStrip
import com.linroid.ketch.app.components.LaneStripCanvas
import com.linroid.ketch.app.components.LaneStripDefaults
import com.linroid.ketch.app.components.StatusDotDefaults
import com.linroid.ketch.app.components.lanePhase
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.percentText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SpeedHistoryStore
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchSpacing
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.list.RowCommands
import com.linroid.ketch.app.ui.list.TaskCommand
import com.linroid.ketch.app.util.LaneHealth
import com.linroid.ketch.app.util.SegmentRate
import com.linroid.ketch.app.util.SegmentRateTracker
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.inspector_connections_active
import ketch.app.shared.generated.resources.inspector_connections_active_speed
import ketch.app.shared.generated.resources.inspector_connections_finished
import ketch.app.shared.generated.resources.inspector_connections_live
import ketch.app.shared.generated.resources.inspector_connections_on_resume
import ketch.app.shared.generated.resources.inspector_connections_unfinished
import ketch.app.shared.generated.resources.inspector_lane_description
import ketch.app.shared.generated.resources.inspector_percent_spoken
import ketch.app.shared.generated.resources.inspector_server_one_connection
import ketch.app.shared.generated.resources.inspector_single_connection
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/**
 * The Connections tab of the inspector: where each connection of an HTTP or FTP download is in
 * the file, and how fast it moves.
 *
 * A summary with the connection stepper sits over a 16 dp map of every segment at its byte
 * offset. One lane per unfinished segment follows, with its own progress, byte range and rate,
 * and a dot that turns amber once it has received nothing for 3 s and red after 10 s. Rates and
 * stalls come from [SpeedHistoryStore.rates]. Hovering a lane, or tapping it on touch, highlights
 * its part of the map. Rows shrink as connections are added, and past 16 the lanes drop their
 * text and sort by rate, labelling the slowest three.
 *
 * @param onHighlight runs with the first byte of the lane the pointer is over, or `null`, so the
 *   inspector can highlight it in its header strip too.
 */
@Composable
fun ConnectionsTab(
  state: AppState,
  row: TaskRow,
  modifier: Modifier = Modifier,
  onHighlight: (Long?) -> Unit = {},
) {
  val rates by state.speedHistory.rates.collectAsState()
  val pending by state.pending.collectAsState()
  // The same key as the Controls' stepper, which shows the change pending too.
  val label = RowCommands.connectionsLabel(row)
  // Lanes, highlight and animations never carry over from another task.
  key(row.key) {
    ConnectionsTabContent(
      row = row,
      rates = rates[row.key].orEmpty(),
      modifier = modifier,
      connectionsPending = (row.key to label) in pending,
      onConnectionsChange = { connections ->
        state.runTaskCommand(
          task = row.task,
          pendingKey = label,
          failure = { device -> TaskCommand.Connections.failure(verbatim(row.name), device) },
        ) { setConnections(connections) }
      },
      onHighlight = onHighlight,
    )
  }
}

/**
 * [ConnectionsTab] for [row] with the [rates] of its connections, without the app state. A
 * place that shows different tasks in turn keys it by task.
 *
 * @param onConnectionsChange asks for a new number of connections; `null` hides the stepper.
 */
@Composable
internal fun ConnectionsTabContent(
  row: TaskRow,
  rates: List<SegmentRate>,
  modifier: Modifier = Modifier,
  connectionsPending: Boolean = false,
  onConnectionsChange: ((Int) -> Unit)? = null,
  onHighlight: (Long?) -> Unit = {},
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val downloading = row.state is DownloadState.Downloading
  val model = remember(row.segments, rates, downloading) {
    connectionsModel(row.segments, rates, downloading)
  }
  var hovered by remember { mutableStateOf<Long?>(null) }
  // A lane that finishes or is re-split under the pointer sends no exit, so drop it here.
  val highlight = hovered?.takeIf { start -> model.lanes.any { it.segment.start == start } }
  val currentOnHighlight by rememberUpdatedState(onHighlight)
  LaunchedEffect(highlight) { currentOnHighlight(highlight) }
  DisposableEffect(Unit) { onDispose { currentOnHighlight(null) } }

  val requested = row.request.connections
  val single = downloading && requested > 1 && row.segments.size == 1
  var singleConfirmed by remember { mutableStateOf(false) }
  LaunchedEffect(single) {
    singleConfirmed = false
    if (single) {
      delay(SegmentRateTracker.STALL_AFTER)
      singleConfirmed = true
    }
  }
  val serverLimited = row.request.resolvedSource?.maxSegments == 1 || singleConfirmed
  val editable = onConnectionsChange != null && row.state.acceptsConnections()

  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(spacing.s3),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier.fillMaxWidth().heightIn(min = KetchTheme.density.buttonSmall),
    ) {
      Text(
        text = connectionsSummary(model, downloading, row.speed).resolve(),
        style = type.numeral,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      if (editable) {
        ConnectionStepper(
          value = requested,
          onCommit = onConnectionsChange,
          autoValue = row.segments.size.takeIf { it > 0 },
          enabled = !serverLimited,
          pending = connectionsPending,
          disabledReason = if (serverLimited) {
            stringResource(Res.string.inspector_server_one_connection)
          } else {
            null
          },
        )
      }
    }
    LaneStrip(
      state = row.state,
      segments = row.segments,
      height = LaneStripDefaults.MapHeight,
      highlight = highlight,
      stalled = model.stalled,
    )
    if (model.lanes.isNotEmpty()) {
      Lanes(
        model = model,
        phase = row.state.lanePhase(),
        initial = remember { model.lanes.mapTo(HashSet()) { it.segment.start } },
        highlight = highlight,
        onHighlight = { hovered = it },
      )
    }
    if (model.finished > 0) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s1),
      ) {
        KetchIconImage(
          icon = KetchIcon.Check,
          size = spacing.s3,
          tint = colors.status.completed.color,
        )
        Text(
          text = Res.plurals.inspector_connections_finished
            .text(model.finished, model.finished, compactSizeText(model.finishedBytes))
            .resolve(),
          style = type.caption,
          color = colors.textSecondary,
        )
      }
    }
    val caption = connectionsCaption(model.lanes.size, serverLimited, editable, downloading)
    if (caption != null) {
      Text(text = caption.resolve(), style = type.caption, color = colors.textTertiary)
    }
  }
}

/**
 * The lanes, at most 320 dp tall and scrolling past that. Lanes that were not there when the
 * tab opened, from a re-split, expand into place.
 */
@Composable
private fun Lanes(
  model: ConnectionsModel,
  phase: LanePhase,
  initial: Set<Long>,
  highlight: Long?,
  onHighlight: (Long?) -> Unit,
) {
  val spacing = KetchTheme.spacing
  val motion = KetchTheme.motion
  val metrics = remember(spacing) { LaneMetrics(spacing) }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(max = metrics.maxHeight)
      .verticalScroll(rememberScrollState()),
  ) {
    for (lane in model.lanes) {
      key(lane.segment.start) {
        val row = @Composable {
          LaneRow(
            lane = lane,
            scale = model.scale,
            phase = phase,
            metrics = metrics,
            highlighted = highlight == lane.segment.start,
            onHighlight = onHighlight,
          )
        }
        if (motion.reduced || lane.segment.start in initial) {
          row()
        } else {
          val visible = remember { MutableTransitionState(false).apply { targetState = true } }
          AnimatedVisibility(
            visibleState = visible,
            enter = expandVertically(tween(motion.medium, easing = motion.easeDecelerate)) +
              fadeIn(tween(motion.medium)),
          ) {
            row()
          }
        }
      }
    }
  }
}

@Composable
private fun LaneRow(
  lane: ConnectionLane,
  scale: LaneScale,
  phase: LanePhase,
  metrics: LaneMetrics,
  highlighted: Boolean,
  onHighlight: (Long?) -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val start = lane.segment.start
  val rate = lane.rate
  val health = rate?.health
  val stalled = health != null && health != LaneHealth.Moving
  val own = remember(lane.segment) { listOf(lane.segment.rebased()) }
  val currentOnHighlight by rememberUpdatedState(onHighlight)
  val currentHighlighted by rememberUpdatedState(highlighted)
  val description = laneDescription(lane).resolve()
  val dense = scale == LaneScale.Dense
  // Dense lanes carry no text, except the slowest three, which get a row tall enough for it.
  val labelled = !dense || lane.annotated
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier
      .fillMaxWidth()
      .height(metrics.rowHeight(if (dense && labelled) LaneScale.Medium else scale))
      .background(
        color = if (highlighted) colors.surfaceHover else Color.Transparent,
        shape = KetchTheme.shapes.xs,
      )
      .pointerInput(start) {
        awaitPointerEventScope {
          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull() ?: continue
            when {
              event.type == PointerEventType.Enter -> currentOnHighlight(start)
              event.type == PointerEventType.Exit -> currentOnHighlight(null)
              event.type == PointerEventType.Release && change.type == PointerType.Touch ->
                currentOnHighlight(if (currentHighlighted) null else start)
            }
          }
        }
      }
      .padding(horizontal = spacing.s1)
      .clearAndSetSemantics { contentDescription = description },
  ) {
    if (labelled) {
      Text(
        text = "#${lane.number}",
        style = type.monoS,
        color = colors.textTertiary,
        maxLines = 1,
        modifier = Modifier.width(metrics.numberWidth),
      )
    } else {
      Spacer(Modifier.width(metrics.numberWidth))
    }
    LaneStripCanvas(
      segments = own,
      phase = phase,
      progress = null,
      height = LaneStripDefaults.LaneHeight,
      heads = phase == LanePhase.Downloading,
      stalled = if (stalled) StalledOwnLane else emptySet(),
      modifier = Modifier.weight(1f).clearAndSetSemantics {},
    )
    if (!dense) {
      Text(
        text = (rate?.stalledFor?.let(::stallText) ?: byteRangeText(start, lane.segment.end + 1))
          .resolve(),
        style = type.caption,
        color = if (health != null) colors.laneTextColor(health) else colors.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.width(metrics.rangeWidth),
      )
    }
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
      if (labelled) {
        Text(
          text = if (rate != null) {
            compactSpeedText(rate.bytesPerSecond)
          } else {
            percentText(lane.percent)
          }.resolve(),
          style = if (dense) type.numeralS else type.numeral,
          color = when {
            rate == null -> colors.textSecondary
            // A dense lane has no range to say it stalled, so its rate does.
            dense && health != null -> colors.laneRateColor(health)
            else -> colors.textPrimary
          },
          textAlign = TextAlign.End,
          maxLines = 1,
          modifier = Modifier.width(metrics.rateWidth).wrapContentHeight(unbounded = true),
        )
      } else {
        Spacer(Modifier.width(metrics.rateWidth))
      }
      if (health != null) {
        KetchDot(color = colors.laneHealthColor(health), size = StatusDotDefaults.TableSize)
      }
    }
  }
}

/** Sizes of the lanes, on the spacing scale. */
private class LaneMetrics(spacing: KetchSpacing) {
  val numberWidth: Dp = spacing.s6
  val rangeWidth: Dp = spacing.s16 + spacing.s4
  val rateWidth: Dp = spacing.s16 + spacing.s2
  val maxHeight: Dp = spacing.s16 * 5
  private val tall: Dp = spacing.s6 + spacing.s1
  private val medium: Dp = spacing.s5
  private val dense: Dp = spacing.s3

  fun rowHeight(scale: LaneScale): Dp = when (scale) {
    LaneScale.Tall -> tall
    LaneScale.Medium -> medium
    LaneScale.Dense -> dense
  }
}

/** How tall the lanes are, from how many there are. */
internal enum class LaneScale {
  /** Up to 8 lanes: 28 dp rows with every detail. */
  Tall,

  /** 9 to 16 lanes: 20 dp rows. */
  Medium,

  /**
   * More than 16: 12 dp rows without text, slowest first. The slowest three keep their number
   * and rate in 20 dp rows.
   */
  Dense;

  companion object {
    fun of(lanes: Int): LaneScale = when {
      lanes <= TALL_LANES -> Tall
      lanes <= MEDIUM_LANES -> Medium
      else -> Dense
    }
  }
}

/**
 * One unfinished segment as a lane.
 *
 * @property rate its rate while the task downloads, `null` otherwise.
 * @property annotated whether a dense lane is one of the three slowest, which keep a label.
 */
@Immutable
internal data class ConnectionLane(
  val segment: Segment,
  val rate: SegmentRate?,
  val annotated: Boolean = false,
) {
  /** "#3" counts from 1. */
  val number: Int
    get() = segment.index + 1

  /** Share of the segment downloaded, in whole percent. */
  val percent: Int
    get() {
      val total = segment.totalBytes
      if (total <= 0) return 100
      return (segment.downloadedBytes.coerceIn(0, total) * 100 / total).toInt()
    }
}

/**
 * The lanes and counts of a task's connections.
 *
 * @property lanes one per unfinished segment, in display order.
 * @property scale how tall the lanes are.
 * @property finished number of finished segments.
 * @property finishedBytes bytes of the finished segments.
 * @property percent share of the whole download done, in whole percent.
 * @property stalled first bytes of the lanes that have received nothing for a while.
 */
@Immutable
internal class ConnectionsModel(
  val lanes: List<ConnectionLane>,
  val scale: LaneScale,
  val finished: Int,
  val finishedBytes: Long,
  val percent: Int,
  val stalled: Set<Long>,
)

/**
 * Lanes of [segments] with their [rates], matched by first byte. While the task is not
 * [downloading] no lane has a rate. Lanes run in byte order; dense ones sort slowest first, by
 * byte order among equals, and while downloading the slowest three are annotated.
 */
internal fun connectionsModel(
  segments: List<Segment>,
  rates: List<SegmentRate>,
  downloading: Boolean,
): ConnectionsModel {
  val byStart = rates.associateBy { it.start }
  val unfinished = segments.filter { !it.isComplete }.sortedBy { it.start }
  val finished = segments.filter { it.isComplete }
  val scale = LaneScale.of(unfinished.size)
  var lanes = unfinished.map { segment ->
    val rate = if (downloading) byStart[segment.start] ?: SegmentRate(segment.start, 0) else null
    ConnectionLane(segment, rate)
  }
  if (scale == LaneScale.Dense && downloading) {
    lanes = lanes.sortedWith(compareBy({ it.rate?.bytesPerSecond ?: 0 }, { it.segment.start }))
      .mapIndexed { index, lane -> lane.copy(annotated = index < ANNOTATED_LANES) }
  }
  return ConnectionsModel(
    lanes = lanes,
    scale = scale,
    finished = finished.size,
    finishedBytes = finished.sumOf { it.totalBytes },
    percent = percentOf(segments),
    stalled = lanes.mapNotNullTo(HashSet()) { lane ->
      lane.segment.start.takeIf { lane.rate?.health?.let { it != LaneHealth.Moving } == true }
    },
  )
}

/**
 * "6 active · 6.4 MB/s" while [downloading] at [speed], else "4 unfinished · 37%". The finished
 * connections have a line of their own under the lanes.
 */
internal fun connectionsSummary(
  model: ConnectionsModel,
  downloading: Boolean,
  speed: Long?,
): UiText {
  val lanes = model.lanes.size
  return when {
    !downloading ->
      Res.plurals.inspector_connections_unfinished.text(lanes, lanes, percentText(model.percent))
    speed != null ->
      Res.plurals.inspector_connections_active_speed.text(lanes, lanes, compactSpeedText(speed))
    else -> Res.plurals.inspector_connections_active.text(lanes)
  }
}

/** Share of [segments] downloaded, in whole percent. */
private fun percentOf(segments: List<Segment>): Int {
  val total = segments.sumOf { it.totalBytes }
  if (total <= 0) return 0
  return (segments.sumOf { it.downloadedBytes.coerceIn(0, it.totalBytes) } * 100 / total).toInt()
}

/**
 * The note under the lanes: a single lane is a single connection, or the most the server allows
 * when [serverLimited]. With more lanes and the stepper shown ([editable]), it says when a change
 * applies.
 */
internal fun connectionsCaption(
  lanes: Int,
  serverLimited: Boolean,
  editable: Boolean,
  downloading: Boolean,
): UiText? = when {
  lanes == 1 && serverLimited -> Res.string.inspector_server_one_connection.text()
  lanes == 1 -> Res.string.inspector_single_connection.text()
  !editable -> null
  downloading -> Res.string.inspector_connections_live.text()
  else -> Res.string.inspector_connections_on_resume.text()
}

/** The color of a lane's dot: green while data arrives, amber once stalled, red when stuck. */
internal fun KetchColors.laneHealthColor(health: LaneHealth): Color = when (health) {
  LaneHealth.Moving -> status.completed.color
  LaneHealth.Stalled -> status.paused.color
  LaneHealth.Stuck -> status.failed.color
}

/** The color of a lane's range, or of the stall that replaces it. */
private fun KetchColors.laneTextColor(health: LaneHealth): Color =
  if (health == LaneHealth.Moving) textTertiary else laneHealthColor(health)

/** The color of a dense lane's rate, which also says that it stalled. */
private fun KetchColors.laneRateColor(health: LaneHealth): Color =
  if (health == LaneHealth.Moving) textPrimary else laneHealthColor(health)

/** What a screen reader says for a lane, such as "Connection 3, 1.4–2.1 GB, 1.3 MB/s". */
private fun laneDescription(lane: ConnectionLane): UiText {
  val rate = lane.rate
  val range = byteRangeText(lane.segment.start, lane.segment.end + 1)
  val status = when {
    rate == null -> Res.string.inspector_percent_spoken.text(lane.percent)
    rate.stalledFor != null -> stallText(rate.stalledFor)
    else -> compactSpeedText(rate.bytesPerSecond)
  }
  return Res.string.inspector_lane_description.text(lane.number, range, status)
}

/** Whether a new number of connections can be asked for in this state. */
private fun DownloadState.acceptsConnections(): Boolean = when (this) {
  is DownloadState.Downloading,
  is DownloadState.Paused,
  is DownloadState.Queued,
  is DownloadState.Scheduled -> true
  is DownloadState.Completed,
  is DownloadState.Failed,
  is DownloadState.Canceled -> false
}

/** This segment as a file of its own, starting at byte 0, for its lane. */
private fun Segment.rebased(): Segment = Segment(index, 0, end - start, downloadedBytes)

private val StalledOwnLane = setOf(0L)
private const val TALL_LANES = 8
private const val MEDIUM_LANES = 16
private const val ANNOTATED_LANES = 3
