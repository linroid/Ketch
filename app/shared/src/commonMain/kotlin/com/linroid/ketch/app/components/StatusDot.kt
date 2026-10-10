package com.linroid.ketch.app.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.RowStatus

/** Sizes of a [StatusDot]. */
object StatusDotDefaults {
  /** List rows, the inspector and the Pulse bar. */
  val Size: Dp = 8.dp

  /** Table rows. */
  val TableSize: Dp = 6.dp
}

/**
 * The status of a row as a small glyph in its status color, with an optional [label]: ● for
 * downloading and starting (with a pulsing halo) and stalled, ○ queued, ◷ scheduled, ◐ paused,
 * ✕ failed, ⊘ canceled and ⚠ for a completed file that has gone missing. A completed row shows
 * no glyph, since success is the default; the space stays so columns line up.
 *
 * Only a failed row colors its label; every other label is secondary text.
 *
 * @param size one of the [StatusDotDefaults] sizes.
 */
@Composable
fun StatusDot(
  status: RowStatus,
  modifier: Modifier = Modifier,
  label: String? = null,
  size: Dp = StatusDotDefaults.Size,
) {
  val colors = KetchTheme.colors
  val pulse = rememberPulse(status == RowStatus.Downloading || status == RowStatus.Starting)
  val motion = KetchTheme.motion
  val color = colors.statusColor(status)
  val glyph = Modifier.size(size).drawWithCache {
    val stroke = Stroke(width = this.size.minDimension * STROKE_SHARE, cap = StrokeCap.Round)
    val triangle = if (status == RowStatus.FileMissing) warningTriangle(this.size.width) else null
    onDrawBehind {
      if (pulse != null) drawHalo(color, pulse.value, motion.pulseAlpha, motion.pulseGrowth)
      drawStatusGlyph(status, color, stroke, triangle)
    }
  }
  if (label == null) {
    Spacer(modifier.then(glyph))
    return
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.iconLabelGap),
    modifier = modifier,
  ) {
    Spacer(glyph)
    Text(
      text = label,
      style = KetchTheme.typography.caption,
      color = if (status == RowStatus.Failed) colors.status.failed.color else colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/**
 * A round dot in [color], such as a connection's health. While [pulse] is set a halo grows from
 * it and fades, every 1.6 s; reduce motion keeps it still.
 */
@Composable
fun KetchDot(
  color: Color,
  modifier: Modifier = Modifier,
  size: Dp = StatusDotDefaults.Size,
  pulse: Boolean = false,
) {
  val motion = KetchTheme.motion
  val halo = rememberPulse(pulse)
  Spacer(
    modifier
      .size(size)
      .drawBehind {
        if (halo != null) drawHalo(color, halo.value, motion.pulseAlpha, motion.pulseGrowth)
        drawCircle(color)
      }
  )
}

/** The color of [status]'s glyph. */
internal fun KetchColors.statusColor(status: RowStatus): Color = when (status) {
  RowStatus.Downloading, RowStatus.Starting -> this.status.downloading.color
  RowStatus.Stalled, RowStatus.Paused -> this.status.paused.color
  RowStatus.Queued -> this.status.queued.color
  RowStatus.Scheduled -> this.status.scheduled.color
  RowStatus.Completed -> this.status.completed.color
  RowStatus.FileMissing -> textTertiary
  RowStatus.Failed -> this.status.failed.color
  RowStatus.Canceled -> this.status.canceled.color
}

/**
 * Progress through one pulse, from 0 to 1 and over again, or `null` when [enabled] is off or
 * motion is reduced. Read it while drawing, so the pulse redraws without recomposing.
 */
@Composable
internal fun rememberPulse(enabled: Boolean): State<Float>? {
  val period = KetchTheme.motion.pulse
  if (!enabled || period <= 0) return null
  return rememberLoop(period, "pulse")
}

/** A value that goes from 0 to 1 over [millis] at a steady pace, then starts over. */
@Composable
internal fun rememberLoop(millis: Int, label: String): State<Float> =
  rememberInfiniteTransition(label = label).animateFloat(
    initialValue = 0f,
    targetValue = 1f,
    animationSpec = infiniteRepeatable(tween(millis, easing = LinearEasing)),
    label = label,
  )

/** A halo [progress] of the way through a pulse: grown by [growth] and faded out. */
internal fun DrawScope.drawHalo(color: Color, progress: Float, alpha: Float, growth: Dp) {
  drawCircle(
    color = color,
    radius = size.minDimension / 2 + growth.toPx() * progress,
    alpha = alpha * (1f - progress),
  )
}

private fun DrawScope.drawStatusGlyph(
  status: RowStatus,
  color: Color,
  stroke: Stroke,
  triangle: Path?,
) {
  val radius = size.minDimension / 2
  val ring = radius - stroke.width / 2
  val reach = radius * MARK_REACH
  when (status) {
    RowStatus.Downloading, RowStatus.Starting, RowStatus.Stalled -> drawCircle(color)
    RowStatus.Queued -> drawCircle(color, ring, style = stroke)
    RowStatus.Scheduled -> {
      drawCircle(color, ring, style = stroke)
      drawMark(color, center, center + Offset(0f, -reach), stroke)
      drawMark(color, center, center + Offset(reach * HAND_SHARE, 0f), stroke)
    }
    RowStatus.Paused -> {
      drawCircle(color, ring, style = stroke)
      drawArc(color, startAngle = 90f, sweepAngle = 180f, useCenter = true)
    }
    RowStatus.Completed -> Unit
    RowStatus.FileMissing -> if (triangle != null) drawPath(triangle, color)
    RowStatus.Failed -> {
      drawMark(color, center + Offset(-reach, -reach), center + Offset(reach, reach), stroke)
      drawMark(color, center + Offset(-reach, reach), center + Offset(reach, -reach), stroke)
    }
    RowStatus.Canceled -> {
      drawCircle(color, ring, style = stroke)
      drawMark(color, center + Offset(-reach, reach), center + Offset(reach, -reach), stroke)
    }
  }
}

private fun DrawScope.drawMark(color: Color, start: Offset, end: Offset, stroke: Stroke) {
  drawLine(color, start, end, strokeWidth = stroke.width, cap = StrokeCap.Round)
}

private fun warningTriangle(size: Float): Path = Path().apply {
  moveTo(size / 2, 0f)
  lineTo(size, size)
  lineTo(0f, size)
  close()
}

/** Width of a glyph's strokes, as a share of its size. */
private const val STROKE_SHARE = 0.18f

/** How far the marks of ✕, ⊘ and ◷ reach from the center, as a share of the radius. */
private const val MARK_REACH = 0.62f

/** Length of the clock's short hand, as a share of the long one. */
private const val HAND_SHARE = 0.8f
