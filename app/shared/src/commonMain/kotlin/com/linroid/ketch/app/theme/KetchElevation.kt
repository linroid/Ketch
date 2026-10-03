package com.linroid.ketch.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.shadow.Shadow
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

/**
 * Drop shadows of each [KetchElevationLevel], drawn in order.
 *
 * @property isDark whether raised surfaces also get the dark theme's top highlight.
 */
@Immutable
data class KetchElevation(
  private val levels: Map<KetchElevationLevel, List<Shadow>>,
  val isDark: Boolean,
) {
  /** Shadows of [level], for `Modifier.dropShadow`. */
  fun shadows(level: KetchElevationLevel): List<Shadow> = levels.getValue(level)
}

/** Elevation of the light or [dark] theme; dark shadows are three times as strong. */
fun ketchElevation(dark: Boolean): KetchElevation {
  val alphaScale = if (dark) 3f else 1f
  fun shadow(y: Int, blur: Int, alpha: Float, spread: Int = 0) = Shadow(
    radius = blur.dp,
    color = ShadowInk.copy(alpha = (alpha * alphaScale).coerceAtMost(1f)),
    spread = spread.dp,
    offset = DpOffset(0.dp, y.dp),
  )
  return KetchElevation(
    levels = mapOf(
      KetchElevationLevel.E0 to emptyList(),
      KetchElevationLevel.E1 to listOf(shadow(1, 2, 0.06f), shadow(8, 24, 0.08f, spread = -4)),
      KetchElevationLevel.E2 to listOf(shadow(1, 2, 0.05f), shadow(4, 12, 0.06f)),
      KetchElevationLevel.E3 to listOf(shadow(4, 12, 0.10f), shadow(16, 40, 0.16f, spread = -8)),
      KetchElevationLevel.E4 to listOf(shadow(12, 24, 0.12f), shadow(32, 64, 0.24f, spread = -12)),
    ),
    isDark = dark,
  )
}

private val ShadowInk = Color(0xFF0F172A)
