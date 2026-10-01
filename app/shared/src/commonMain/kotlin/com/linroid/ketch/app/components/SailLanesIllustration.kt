package com.linroid.ketch.app.components

import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.theme.KetchTheme

/** Sizes of a [SailLanesIllustration]. */
object SailLanesIllustrationDefaults {
  /** Width on wide windows. */
  val Width: Dp = 280.dp

  /** Width on phones. */
  val CompactWidth: Dp = 200.dp
}

/**
 * The brand motif: a ketch whose two sails are split into download lanes, drawn in one canvas.
 *
 * The main sail has six lanes and the mizzen four, their widths following the sails' edges,
 * over a hull lane and a faint accent and ember glow. When [animate] is set the lanes fill from
 * left to right in the accent ramp, the top one in ember, one after another, hold for two
 * seconds and start over every five. Under reduce motion, or with [animate] off, they stay full.
 * It is decorative, so screen readers skip it.
 *
 * @param width the illustration's width; its height keeps the 280 × 200 proportions.
 */
@Composable
fun SailLanesIllustration(
  modifier: Modifier = Modifier,
  animate: Boolean = true,
  width: Dp = SailLanesIllustrationDefaults.Width,
) {
  val colors = KetchTheme.colors
  val motion = KetchTheme.motion
  val easing = motion.easeStandard
  val time: State<Float>? = if (animate && !motion.reduced) {
    rememberInfiniteTransition(label = "sailLanes").animateFloat(
      initialValue = 0f,
      targetValue = LOOP_MILLIS.toFloat(),
      animationSpec = infiniteRepeatable(tween(LOOP_MILLIS, easing = LinearEasing)),
      label = "sailLanes",
    )
  } else {
    null
  }
  Spacer(
    modifier
      .size(width, width * (REFERENCE_HEIGHT / REFERENCE_WIDTH))
      .clearAndSetSemantics {}
      .drawWithCache {
        val unit = size.width / REFERENCE_WIDTH
        val radius = CornerRadius(LANE_RADIUS * unit)
        val lanes = SailLanes.map { lane ->
          Offset(lane.x * unit, lane.y * unit) to Size(lane.width * unit, LANE_HEIGHT * unit)
        }
        val hullTopLeft = Offset(HULL_X * unit, HULL_Y * unit)
        val hullSize = Size(HULL_WIDTH * unit, LANE_HEIGHT * unit)
        val (topLeft, topSize) = lanes.first()
        val ember = Brush.horizontalGradient(
          colors = colors.brandEmber,
          startX = topLeft.x,
          endX = topLeft.x + topSize.width,
        )
        val accentCenter = Offset(ACCENT_GLOW_X * unit, ACCENT_GLOW_Y * unit)
        val emberCenter = Offset(EMBER_GLOW_X * unit, EMBER_GLOW_Y * unit)
        val accentGlow = Brush.radialGradient(
          colors = listOf(colors.accent.copy(alpha = GLOW_ALPHA), Color.Transparent),
          center = accentCenter,
          radius = ACCENT_GLOW_RADIUS * unit,
        )
        val emberGlow = Brush.radialGradient(
          colors = listOf(colors.brandEmber.last().copy(alpha = GLOW_ALPHA), Color.Transparent),
          center = emberCenter,
          radius = EMBER_GLOW_RADIUS * unit,
        )
        onDrawBehind {
          // The glows fade out past the canvas, so they are drawn whole rather than cut square.
          drawGlow(accentGlow, accentCenter, ACCENT_GLOW_RADIUS * unit)
          drawGlow(emberGlow, emberCenter, EMBER_GLOW_RADIUS * unit)
          val now = time?.value
          val alpha = if (now == null) 1f else sailFillAlpha(now)
          lanes.forEachIndexed { i, (laneTopLeft, laneSize) ->
            drawRoundRect(colors.surfaceSunken, laneTopLeft, laneSize, radius)
            val fill = if (now == null) 1f else sailLaneFill(i, now, easing)
            if (fill <= 0f) return@forEachIndexed
            val filled = Size(laneSize.width * fill, laneSize.height)
            if (i == 0) {
              drawRoundRect(ember, laneTopLeft, filled, radius, alpha = alpha)
            } else {
              drawRoundRect(
                color = colors.lanes[i % colors.lanes.size],
                topLeft = laneTopLeft,
                size = filled,
                cornerRadius = radius,
                alpha = alpha,
              )
            }
          }
          drawRoundRect(colors.textTertiary, hullTopLeft, hullSize, radius, alpha = HULL_ALPHA)
        }
      }
  )
}

/** Fills the square around [center] that holds the whole of a radial [glow] of [radius]. */
private fun DrawScope.drawGlow(glow: Brush, center: Offset, radius: Float) {
  drawRect(glow, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2))
}

/**
 * How full lane [lane] is [time] milliseconds into the loop: each lane starts 90 ms after the
 * one before and fills over [FILL_MILLIS], eased by [easing].
 */
internal fun sailLaneFill(lane: Int, time: Float, easing: Easing = LinearEasing): Float {
  val progress = (time - lane * STAGGER_MILLIS) / FILL_MILLIS
  return easing.transform(progress.coerceIn(0f, 1f))
}

/**
 * Opacity of the fills [time] milliseconds into the loop: full while they fill and hold, then
 * fading out before the loop starts over.
 */
internal fun sailFillAlpha(time: Float): Float {
  val fadeStart = (SailLanes.size - 1) * STAGGER_MILLIS + FILL_MILLIS + HOLD_MILLIS
  if (time <= fadeStart) return 1f
  return (1f - (time - fadeStart) / (LOOP_MILLIS - fadeStart)).coerceIn(0f, 1f)
}

/** One lane, in dp of the 280 × 200 reference box. */
private class SailLane(val x: Float, val y: Float, val width: Float)

/** Lanes in the order they fill: row by row, the main sail before the mizzen. */
private val SailLanes: List<SailLane> = buildList {
  val mainWidths = floatArrayOf(18f, 38f, 56f, 70f, 84f, 96f)
  val mizzenWidths = floatArrayOf(18f, 36f, 50f, 62f)
  for (row in mainWidths.indices) {
    val y = SAIL_TOP + row * (LANE_HEIGHT + LANE_GAP)
    add(SailLane(MAIN_X, y, mainWidths[row]))
    val mizzenRow = row - (mainWidths.size - mizzenWidths.size)
    if (mizzenRow >= 0) add(SailLane(MIZZEN_X, y, mizzenWidths[mizzenRow]))
  }
}

private const val REFERENCE_WIDTH = 280f
private const val REFERENCE_HEIGHT = 200f
private const val LANE_HEIGHT = 14f
private const val LANE_GAP = 4f
private const val LANE_RADIUS = 3f
private const val SAIL_TOP = 36f
private const val MAIN_X = 59f
private const val MIZZEN_X = 169f
private const val HULL_X = 29f
private const val HULL_Y = 150f
private const val HULL_WIDTH = 222f
private const val HULL_ALPHA = 0.2f
private const val GLOW_ALPHA = 0.12f
private const val ACCENT_GLOW_X = 110f
private const val ACCENT_GLOW_Y = 96f
private const val ACCENT_GLOW_RADIUS = 130f
private const val EMBER_GLOW_X = 196f
private const val EMBER_GLOW_Y = 132f
private const val EMBER_GLOW_RADIUS = 96f
private const val LOOP_MILLIS = 5000
private const val STAGGER_MILLIS = 90f
private const val FILL_MILLIS = 1600f
private const val HOLD_MILLIS = 2000f
