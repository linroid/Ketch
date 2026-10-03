package com.linroid.ketch.app.components

import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.formatBytes
import kotlin.math.roundToInt

/**
 * One series of a [KetchSpeedChart], stacked on the series before it.
 *
 * @property samples speeds in bytes per second, oldest first, one per sample interval.
 * @property color the series' line; the band under it is filled with the same color, faintly.
 */
@Immutable
class SpeedBand(val samples: List<Long>, val color: Color)

/**
 * A dashed line across a [KetchSpeedChart] at a speed limit.
 *
 * @property bytesPerSecond the limit.
 * @property label what limits, such as "Task" or "Global", written at the end of the line.
 */
@Immutable
data class SpeedLimitLine(val bytesPerSecond: Long, val label: String? = null)

/**
 * Speed over time, from the 14 dp sparkline of the Pulse bar to the 120 dp chart of the
 * Activity tab.
 *
 * [bands] stack from the bottom up, such as one per connection or one per device in its pennant
 * hue. They line up at their latest sample on the right edge, so a short history fills in from
 * the right. The y-axis runs from zero to a round ceiling above the peak and every limit, which
 * [showAxis] labels top-left. Limits are dashed lines in the throttled color. With [timeLabel],
 * hovering (or dragging on touch) shows a crosshair labelled with the sample's time and total
 * speed, such as "11:42:08 · 9.8 MB/s". [modifier] gives the chart its size.
 *
 * @param slots samples the width holds; the longest band's by default.
 * @param showAxis whether to label the ceiling; sparklines leave it off.
 * @param timeLabel time of the sample in a slot, counted from the left.
 */
@Composable
fun KetchSpeedChart(
  bands: List<SpeedBand>,
  modifier: Modifier = Modifier,
  limits: List<SpeedLimitLine> = emptyList(),
  slots: Int = bands.maxOfOrNull { it.samples.size } ?: 0,
  showAxis: Boolean = true,
  timeLabel: ((index: Int) -> String)? = null,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val measurer = rememberTextMeasurer()
  val totals = remember(bands, slots) { stackedTotals(bands, slots) }
  val peak = totals.maxOrNull() ?: 0L
  val top = remember(peak, limits) { speedChartCeiling(peak, limits) }
  val axisLabel = if (showAxis) {
    remember(top, type) { measurer.measure(formatSpeedCeiling(top), type.numeralS) }
  } else {
    null
  }
  val limitLabels = remember(limits, type) {
    limits.map { limit -> limit.label?.let { measurer.measure(it, type.numeralS) } }
  }
  val description = remember(totals) {
    val now = totals.lastOrNull() ?: 0L
    "Speed ${formatBytes(now)}/s, peak ${formatBytes(peak)}/s"
  }
  var hover by remember { mutableStateOf<Int?>(null) }
  val hoverable = timeLabel != null && slots > 1
  val pointer = if (hoverable) {
    Modifier.pointerInput(slots) {
      awaitPointerEventScope {
        while (true) {
          val event = awaitPointerEvent()
          val change = event.changes.firstOrNull() ?: continue
          val left = event.type == PointerEventType.Exit ||
            change.type == PointerType.Touch && !change.pressed
          hover = if (left) null else slotAt(change.position.x, size.width.toFloat(), slots)
        }
      }
    }
  } else {
    Modifier
  }

  Spacer(
    modifier
      .semantics { contentDescription = description }
      .then(pointer)
      .drawWithCache {
        val stroke = Stroke(
          width = LineWidth.toPx(),
          cap = StrokeCap.Round,
          join = StrokeJoin.Round,
        )
        val inset = stroke.width / 2
        val paths = bandPaths(bands, slots, top, size, inset)
        val dash = PathEffect.dashPathEffect(floatArrayOf(DashOn.toPx(), DashOff.toPx()))
        val labelGap = LabelGap.toPx()
        onDrawBehind {
          // A labelled chart keeps its baseline, so it still reads as a chart before any data.
          if (axisLabel != null) {
            val y = size.height - LimitWidth.toPx() / 2
            drawLine(
              color = colors.hairline,
              start = Offset(0f, y),
              end = Offset(size.width, y),
              strokeWidth = LimitWidth.toPx(),
            )
          }
          paths.forEachIndexed { i, (fill, line) ->
            val color = bands[i].color
            drawPath(fill, color, alpha = FILL_ALPHA)
            drawPath(line, color, style = stroke)
          }
          limits.forEachIndexed { i, limit ->
            val y = chartY(limit.bytesPerSecond, top, size.height, inset)
            drawLine(
              color = colors.status.paused.color,
              start = Offset(0f, y),
              end = Offset(size.width, y),
              strokeWidth = LimitWidth.toPx(),
              pathEffect = dash,
            )
            val label = limitLabels.getOrNull(i) ?: return@forEachIndexed
            val labelTop = (y - label.size.height - labelGap).coerceAtLeast(0f)
            drawText(
              textLayoutResult = label,
              color = colors.status.paused.color,
              topLeft = Offset(size.width - label.size.width, labelTop),
            )
          }
          if (axisLabel != null) drawText(axisLabel, colors.textTertiary, Offset.Zero)
          val index = hover
          if (index != null && index < totals.size && timeLabel != null) {
            val x = slotX(index, slots, size.width)
            drawLine(
              color = colors.textTertiary,
              start = Offset(x, 0f),
              end = Offset(x, size.height),
              strokeWidth = LimitWidth.toPx(),
            )
            val y = chartY(totals[index], top, size.height, inset)
            drawCircle(colors.accent, radius = stroke.width * 2, center = Offset(x, y))
            val text = measurer.measure(
              "${timeLabel(index)} · ${formatBytes(totals[index])}/s",
              type.numeralS
            )
            val pad = labelGap * 2
            val boxWidth = text.size.width + pad * 2
            val boxLeft = (x - boxWidth / 2).coerceIn(0f, (size.width - boxWidth).coerceAtLeast(0f))
            drawRoundRect(
              color = colors.inverseSurface,
              topLeft = Offset(boxLeft, 0f),
              size = Size(boxWidth, text.size.height + labelGap * 2),
              cornerRadius = CornerRadius(pad),
            )
            drawText(text, colors.inverseOnSurface, Offset(boxLeft + pad, labelGap))
          }
        }
      }
  )
}

/**
 * Stacked total of [bands] in each of [slots] slots, the bands lined up at their latest sample
 * in the last slot.
 */
internal fun stackedTotals(bands: List<SpeedBand>, slots: Int): LongArray {
  val totals = LongArray(slots.coerceAtLeast(0))
  for (band in bands) {
    val offset = slots - band.samples.size
    for (slot in maxOf(0, offset) until slots) {
      totals[slot] += band.samples[slot - offset].coerceAtLeast(0)
    }
  }
  return totals
}

/** The top of a chart whose stacked speed peaks at [peak] and that shows [limits]. */
internal fun speedChartCeiling(peak: Long, limits: List<SpeedLimitLine>): Long {
  val highest = limits.maxOfOrNull { it.bytesPerSecond } ?: 0L
  return niceSpeedCeiling(maxOf(peak, highest))
}

/**
 * The smallest round speed at or above [bytesPerSecond]: 1, 2 or 5 times a power of ten of a
 * byte unit, where 500 of a unit rounds up to 1 of the next (700 KB/s gives 1 MB/s). Never below
 * 1 KB/s, so an idle chart still has a scale.
 */
internal fun niceSpeedCeiling(bytesPerSecond: Long): Long {
  val wanted = maxOf(bytesPerSecond, KIB)
  var unit = 1L
  while (true) {
    for (step in NICE_STEPS) {
      val candidate = step * unit
      if (candidate >= wanted) return candidate
    }
    if (unit > Long.MAX_VALUE / KIB / NICE_STEPS.last()) return wanted
    unit *= KIB
  }
}

/** [bytesPerSecond] as the axis writes a round ceiling: "10 MB/s", not "10.0 MB/s". */
internal fun formatSpeedCeiling(bytesPerSecond: Long): String =
  formatBytes(bytesPerSecond).replace(TrailingZeros, "") + "/s"

/** Slot under [x] in a chart [width] wide holding [slots] samples. */
internal fun slotAt(x: Float, width: Float, slots: Int): Int? {
  if (slots < 2 || width <= 0f) return null
  return (x / width * (slots - 1)).roundToInt().coerceIn(0, slots - 1)
}

private fun slotX(slot: Int, slots: Int, width: Float): Float =
  if (slots > 1) slot.toFloat() / (slots - 1) * width else width

private fun chartY(bytesPerSecond: Long, ceiling: Long, height: Float, inset: Float): Float {
  val share = (bytesPerSecond.toDouble() / ceiling).toFloat().coerceIn(0f, 1f)
  return inset + (height - inset * 2) * (1f - share)
}

/** For each band, its filled area and its top line. */
private fun bandPaths(
  bands: List<SpeedBand>,
  slots: Int,
  ceiling: Long,
  size: Size,
  inset: Float,
): List<Pair<Path, Path>> {
  val base = LongArray(slots.coerceAtLeast(0))
  return bands.map { band ->
    val line = Path()
    val fill = Path()
    val offset = slots - band.samples.size
    val first = maxOf(0, offset)
    fun sample(slot: Int): Long = band.samples[slot - offset].coerceAtLeast(0)
    if (slots - first >= 2) {
      for (slot in first until slots) {
        val x = slotX(slot, slots, size.width)
        val y = chartY(base[slot] + sample(slot), ceiling, size.height, inset)
        if (slot == first) line.moveTo(x, y) else line.lineTo(x, y)
        if (slot == first) fill.moveTo(x, y) else fill.lineTo(x, y)
      }
      for (slot in slots - 1 downTo first) {
        fill.lineTo(slotX(slot, slots, size.width), chartY(base[slot], ceiling, size.height, inset))
      }
      fill.close()
    }
    for (slot in first until slots) base[slot] += sample(slot)
    fill to line
  }
}

private const val KIB = 1024L
private val NICE_STEPS = longArrayOf(1, 2, 5, 10, 20, 50, 100, 200, 500)
private const val FILL_ALPHA = 0.12f
private val TrailingZeros = Regex("""\.0+(?= )""")
private val LineWidth = 1.5.dp
private val LimitWidth = 1.dp
private val DashOn = 4.dp
private val DashOff = 3.dp
private val LabelGap = 2.dp
