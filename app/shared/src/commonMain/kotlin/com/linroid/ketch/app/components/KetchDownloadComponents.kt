package com.linroid.ketch.app.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchTheme

/**
 * Segmented progress bar, drawn as a [LaneStrip] without write heads.
 *
 * @param progress per-segment fraction in [0, 1]; its size sets the segment count.
 * @param widths per-segment relative width; equal by default.
 * @param trackColor ignored; lanes run on the sunken surface.
 * @param showSeams ignored; seams separate unfinished segments.
 */
@Deprecated("Use LaneStrip with the task's state and segments.")
@Suppress("UNUSED_PARAMETER")
@Composable
fun KetchSegmentBar(
  progress: List<Float>,
  modifier: Modifier = Modifier,
  widths: List<Float>? = null,
  height: Dp = 10.dp,
  trackColor: Color = KetchTheme.colors.surfaceSunken,
  showSeams: Boolean = true,
) {
  val segments = remember(progress, widths) { segmentsOf(progress, widths) }
  LaneStripCanvas(
    segments = segments,
    phase = LanePhase.Downloading,
    progress = null,
    modifier = modifier,
    height = height,
    heads = false,
  )
}

/**
 * Per-segment rows: an index, a lane of that segment's progress, its percentage and a health
 * dot (green above 0.8, amber above 0.5, red below).
 *
 * @param health per-segment health in [0, 1].
 */
@Deprecated("The inspector's Connections tab shows segments with LaneStrip.")
@Composable
fun KetchSegmentDetail(
  progress: List<Float>,
  health: List<Float>,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  Column(
    verticalArrangement = Arrangement.spacedBy(if (compact) spacing.s0_5 else spacing.s1),
    modifier = modifier.fillMaxWidth(),
  ) {
    progress.forEachIndexed { i, fraction ->
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        modifier = Modifier.fillMaxWidth().height(if (compact) CompactRowHeight else RowHeight),
      ) {
        Text(
          text = "#${i + 1}",
          style = type.monoS,
          color = colors.textTertiary,
          modifier = Modifier.width(IndexWidth),
        )
        val segment = remember(fraction) { segmentsOf(listOf(fraction), widths = null) }
        LaneStripCanvas(
          segments = segment,
          phase = LanePhase.Downloading,
          progress = null,
          modifier = Modifier.weight(1f),
          height = if (compact) LaneStripDefaults.CellHeight else LaneStripDefaults.LaneHeight,
          heads = false,
        )
        if (!compact) {
          Text(
            text = "${(fraction.coerceIn(0f, 1f) * 100).toInt()}%",
            style = type.numeralS,
            color = colors.textTertiary,
            modifier = Modifier.width(PercentWidth),
          )
        }
        KetchDot(
          color = colors.segmentHealth(health.getOrElse(i) { 1f }),
          size = if (compact) StatusDotDefaults.TableSize else StatusDotDefaults.Size,
        )
      }
    }
  }
}

private fun KetchColors.segmentHealth(health: Float): Color = when {
  health > 0.8f -> status.completed.color
  health > 0.5f -> status.paused.color
  else -> status.failed.color
}

/** Segments spanning [widths] of a notional file, each [progress] of the way done. */
private fun segmentsOf(progress: List<Float>, widths: List<Float>?): List<Segment> {
  val shares = progress.indices.map { widths?.getOrNull(it)?.coerceAtLeast(0f) ?: 1f }
  val sum = shares.sum().takeIf { it > 0f } ?: return emptyList()
  var start = 0L
  return progress.mapIndexed { i, fraction ->
    val length = (shares[i] / sum * NOTIONAL_SIZE).toLong().coerceAtLeast(1)
    val segment = Segment(
      index = i,
      start = start,
      end = start + length - 1,
      downloadedBytes = (fraction.coerceIn(0f, 1f) * length).toLong(),
    )
    start += length
    segment
  }
}

private const val NOTIONAL_SIZE = 1_000_000L
private val RowHeight = 20.dp
private val CompactRowHeight = 14.dp
private val IndexWidth = 22.dp
private val PercentWidth = 38.dp
