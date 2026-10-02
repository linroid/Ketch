package com.linroid.ketch.app.theme

import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.dp

/**
 * Corner radii. A shape nested in another uses the outer radius minus the inset: a row
 * highlight inset 4 dp in the r16 card uses r12.
 *
 * @property xs 4 dp: lanes, progress tracks and the inner focus ring.
 * @property sm 8 dp: compact inputs, icon buttons, tooltips and sidebar items.
 * @property md 12 dp: menus, popovers, toasts, comfortable inputs and problem cards.
 * @property lg 16 dp: the content card, the inspector, device cards and settings groups.
 * @property xl 20 dp: dialogs, the intake sheet and the command palette.
 * @property xxl 28 dp: the top corners of bottom sheets.
 * @property full pill: buttons, chips, segmented controls, tabs, pennants and the FAB.
 * @property textField inputs: [sm] when compact, [md] when comfortable.
 */
@Immutable
data class KetchShapes(
  val xs: CornerBasedShape = RoundedCornerShape(4.dp),
  val sm: CornerBasedShape = RoundedCornerShape(8.dp),
  val md: CornerBasedShape = RoundedCornerShape(12.dp),
  val lg: CornerBasedShape = RoundedCornerShape(16.dp),
  val xl: CornerBasedShape = RoundedCornerShape(20.dp),
  val xxl: CornerBasedShape = RoundedCornerShape(28.dp),
  val full: CornerBasedShape = RoundedCornerShape(percent = 50),
  val textField: CornerBasedShape = sm,
) {
  /** Buttons. */
  val button: CornerBasedShape get() = full

  /** Cards. */
  val card: CornerBasedShape get() = lg

  /** Dialogs. */
  val dialog: CornerBasedShape get() = xl

  /** Menus and popovers. */
  val menu: CornerBasedShape get() = md

  /** Toasts. */
  val toast: CornerBasedShape get() = md

  /** Count badges. */
  val badge: CornerBasedShape get() = full

  /** Progress tracks and lanes. */
  val progressBar: CornerBasedShape get() = xs

  /** Sidebar items. */
  val sidebarItem: CornerBasedShape get() = sm

  /** Bottom sheets, rounded at the top only. */
  val sheetTop: CornerBasedShape =
    xxl.copy(bottomEnd = ZeroCornerSize, bottomStart = ZeroCornerSize)
}

/** The radius scale at [density]. */
fun ketchShapes(density: KetchDensity = KetchDensity.Compact): KetchShapes {
  val shapes = KetchShapes()
  return if (density == KetchDensity.Comfortable) shapes.copy(textField = shapes.md) else shapes
}
