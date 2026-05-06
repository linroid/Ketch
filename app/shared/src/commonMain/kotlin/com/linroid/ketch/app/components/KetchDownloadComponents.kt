package com.linroid.ketch.app.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.theme.KetchTheme
import kotlin.math.max

/**
 * Segmented progress bar — Ketch's signature visualization.
 *
 * Each parallel HTTP range is shown as one stripe, all stacked into a single
 * progress track. The accent hue cycles through the 8-color [segments] palette
 * so adjacent stripes stay distinguishable even on long bars.
 *
 * @param progress  per-segment fraction in [0, 1]; size determines segment count.
 * @param widths    per-segment relative width; defaults to equal split.
 * @param showSeams draw 1px gaps between segments (matches the JS mock).
 */
@Composable
fun KetchSegmentBar(
  progress: List<Float>,
  modifier: Modifier = Modifier,
  widths: List<Float>? = null,
  height: Dp = 10.dp,
  trackColor: Color = KetchTheme.colors.outlineVariant,
  showSeams: Boolean = true,
) {
  val palette = KetchTheme.colors.segments
  val n = progress.size
  if (n == 0) return
  val ws = widths ?: List(n) { 1f / n }
  val seamColor = KetchTheme.colors.background

  Row(
    modifier = modifier
      .fillMaxWidth()
      .height(height)
      .clip(RoundedCornerShape(height / 2))
      .background(trackColor),
  ) {
    progress.forEachIndexed { i, p ->
      val w = ws.getOrElse(i) { 1f / n }
      Box(Modifier.weight(max(w, 0.0001f)).fillMaxHeight()) {
        Box(
          Modifier
            .fillMaxWidth(p.coerceIn(0f, 1f))
            .fillMaxHeight()
            .background(palette[i % palette.size]),
        )
      }
      if (showSeams && i < n - 1) {
        Box(Modifier.width(1.dp).fillMaxHeight().background(seamColor))
      }
    }
  }
}

/**
 * Detailed segment view — N rows, each a mini bar with byte offset, percent,
 * and a health dot. Used in the download row's expanded panel.
 *
 * `health` is in [0, 1]; values below 0.6 dim the fill and overlay a striped
 * warning pattern (rendered here as a flat warning tint to keep the renderer
 * portable across platforms).
 */
@Composable
fun KetchSegmentDetail(
  progress: List<Float>,
  health: List<Float>,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val rowH = if (compact) 14.dp else 20.dp
  val barH = if (compact) 6.dp else 8.dp

  Column(modifier = modifier.fillMaxWidth()) {
    progress.forEachIndexed { i, p ->
      val h = health.getOrElse(i) { 1f }
      val color = colors.segments[i % colors.segments.size]
      Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().height(rowH),
      ) {
        Text(
          text = "#${i + 1}",
          style = type.monoXSmall.copy(color = colors.onSurfaceDim),
          modifier = Modifier.width(22.dp),
        )
        Spacer(Modifier.width(8.dp))
        Box(
          Modifier
            .weight(1f)
            .height(barH)
            .clip(RoundedCornerShape(2.dp))
            .background(colors.outlineVariant),
        ) {
          Box(
            Modifier
              .fillMaxWidth(p.coerceIn(0f, 1f))
              .fillMaxHeight()
              .background(if (h < 0.6f) color.copy(alpha = 0.4f) else color),
          )
          if (h < 0.6f) {
            Box(
              Modifier
                .fillMaxSize()
                .background(colors.warning.copy(alpha = 0.18f)),
            )
          }
        }
        if (!compact) {
          Spacer(Modifier.width(8.dp))
          Text(
            text = "${(p * 100).toInt()}%",
            style = type.monoXSmall.copy(color = colors.onSurfaceDim),
            modifier = Modifier.width(38.dp),
          )
        }
        Spacer(Modifier.width(8.dp))
        HealthDot(value = h, size = if (compact) 5.dp else 6.dp)
      }
      if (i < progress.size - 1) Spacer(Modifier.height(if (compact) 3.dp else 5.dp))
    }
  }
}

@Composable
private fun HealthDot(value: Float, size: Dp = 6.dp) {
  val colors = KetchTheme.colors
  val c = when {
    value > 0.8f -> colors.success
    value > 0.5f -> colors.warning
    else         -> colors.error
  }
  Box(
    modifier = Modifier
      .size(size)
      .clip(RoundedCornerShape(50))
      .background(c),
  )
}

/**
 * Speed sparkline — area + 1.5dp top stroke. Normalises against the sample max
 * so the line always fills the canvas; pass [normalize] = false if the caller
 * already scaled the values into [0, 1].
 */
@Composable
fun KetchSpeedChart(
  samples: List<Float>,
  modifier: Modifier = Modifier,
  height: Dp = 80.dp,
  lineColor: Color = KetchTheme.colors.primary,
  normalize: Boolean = true,
) {
  Canvas(modifier = modifier.fillMaxWidth().height(height)) {
    if (samples.size < 2) return@Canvas
    val max = if (normalize) samples.max().coerceAtLeast(0.0001f) else 1f
    val w = size.width
    val h = size.height
    val step = w / (samples.size - 1)
    val line = Path()
    val fill = Path()
    fill.moveTo(0f, h)
    samples.forEachIndexed { i, v ->
      val x = i * step
      val y = h - (v / max).coerceIn(0f, 1f) * (h - 4f) - 2f
      if (i == 0) line.moveTo(x, y) else line.lineTo(x, y)
      fill.lineTo(x, y)
    }
    fill.lineTo(w, h)
    fill.close()

    drawPath(fill, color = lineColor.copy(alpha = 0.18f))
    drawPath(
      path = line,
      color = lineColor,
      style = Stroke(width = 1.5f, cap = StrokeCap.Round),
    )
  }
}

/**
 * Single row in the download queue — DS skeleton.
 *
 * Wrap in a clickable container if you want the row to expand on tap; the row
 * itself only renders the presentational layer (file name, single thin
 * progress track, primary metric).
 */
@Composable
fun KetchDownloadRow(
  name: String,
  progress: Float,
  primaryMetric: String,
  modifier: Modifier = Modifier,
  trackColor: Color = KetchTheme.colors.outlineVariant,
  fillColor: Color = KetchTheme.colors.primary,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(horizontal = 16.dp, vertical = 12.dp),
  ) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = name,
        style = type.bodyLarge.copy(color = colors.onBackground),
        modifier = Modifier.weight(1f),
      )
      Text(
        text = primaryMetric,
        style = type.monoSmall.copy(color = colors.onSurfaceVariant),
      )
    }
    Spacer(Modifier.height(6.dp))
    KetchProgressBar(
      progress = progress,
      trackColor = trackColor,
      fillColor = fillColor,
    )
  }
}
