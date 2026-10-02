package com.linroid.ketch.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** Elevation levels; [Modifier.ketchSurface] draws their shadows. */
enum class KetchElevationLevel {
  /** Flat: rows, the table and the sidebar. */
  E0,

  /** The content card and the docked inspector. */
  E1,

  /** Device cards, hovered pills, the selection bar and the Pulse popover. */
  E2,

  /** Menus, toasts, tooltips, popovers and the overlay inspector. */
  E3,

  /** Dialogs, the intake sheet and the command palette. */
  E4,
}

/** One drop shadow, as a design tool describes it (x/y offset, blur, spread). */
@Immutable
data class ShadowLayer(
  val offsetX: Dp,
  val offsetY: Dp,
  val blur: Dp,
  val spread: Dp,
  val color: Color,
) {
  /** This layer as a Compose [Shadow] for `Modifier.dropShadow`. */
  fun toShadow(): Shadow = Shadow(
    radius = blur,
    color = color,
    spread = spread,
    offset = DpOffset(offsetX, offsetY),
  )
}

/**
 * Shadow layers of each [KetchElevationLevel], drawn in order.
 *
 * @property isDark whether raised surfaces also get the dark theme's top highlight.
 */
@Immutable
data class KetchElevation(
  val e0: List<ShadowLayer>,
  val e1: List<ShadowLayer>,
  val e2: List<ShadowLayer>,
  val e3: List<ShadowLayer>,
  val e4: List<ShadowLayer>,
  val isDark: Boolean,
) {
  /** Shadow layers of [level]. */
  fun layers(level: KetchElevationLevel): List<ShadowLayer> = when (level) {
    KetchElevationLevel.E0 -> e0
    KetchElevationLevel.E1 -> e1
    KetchElevationLevel.E2 -> e2
    KetchElevationLevel.E3 -> e3
    KetchElevationLevel.E4 -> e4
  }
}

/** Elevation of the light or [dark] theme; dark shadows are three times as strong. */
fun ketchElevation(dark: Boolean): KetchElevation {
  val alphaScale = if (dark) 3f else 1f
  fun layer(y: Int, blur: Int, alpha: Float, spread: Int = 0) = ShadowLayer(
    offsetX = 0.dp,
    offsetY = y.dp,
    blur = blur.dp,
    spread = spread.dp,
    color = ShadowInk.copy(alpha = (alpha * alphaScale).coerceAtMost(1f)),
  )
  return KetchElevation(
    e0 = emptyList(),
    e1 = listOf(layer(1, 2, 0.06f), layer(8, 24, 0.08f, spread = -4)),
    e2 = listOf(layer(1, 2, 0.05f), layer(4, 12, 0.06f)),
    e3 = listOf(layer(4, 12, 0.10f), layer(16, 40, 0.16f, spread = -8)),
    e4 = listOf(layer(12, 24, 0.12f), layer(32, 64, 0.24f, spread = -12)),
    isDark = dark,
  )
}

private val ShadowInk = Color(0xFF0F172A)
