package com.linroid.ketch.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Spacing on a 4-point grid (`s1` = 4 dp) and the layout constants of the app's regions.
 *
 * Off-grid literals are kept to `components/`. Sizes that depend on touch or pointer input
 * live in [KetchDensity].
 *
 * @property sidebarWidth width of the expanded sidebar.
 * @property railWidth width of the rail when the sidebar is collapsed or the window is medium.
 * @property cardInset space around the content card, except on the sidebar's side.
 * @property pageHeaderHeight height of a page header.
 * @property pageHeaderPadding horizontal padding of a page header.
 * @property tabRowHeight height of the status tab row.
 * @property tableHeaderHeight height of the table header.
 * @property tableRowCompact table row height with the "Compact rows" preference.
 * @property tableGroupHeaderHeight height of a group header in the table.
 * @property pulseBarHeight height of the Pulse bar.
 * @property inspectorWidth default width of the docked inspector.
 * @property inspectorMinWidth narrowest the docked inspector can be dragged to.
 * @property inspectorMaxWidth widest the docked inspector can be dragged to.
 * @property iconLabelGap gap between a glyph and its label.
 * @property rowPadding horizontal padding of rows.
 * @property cellPadding horizontal padding of table cells.
 * @property sectionGap gap between sections of a page.
 */
@Immutable
data class KetchSpacing(
  val s0_5: Dp = 2.dp,
  val s1: Dp = 4.dp,
  val s2: Dp = 8.dp,
  val s3: Dp = 12.dp,
  val s4: Dp = 16.dp,
  val s5: Dp = 20.dp,
  val s6: Dp = 24.dp,
  val s8: Dp = 32.dp,
  val s10: Dp = 40.dp,
  val s12: Dp = 48.dp,
  val s16: Dp = 64.dp,
  val sidebarWidth: Dp = 220.dp,
  val railWidth: Dp = 72.dp,
  val cardInset: Dp = 8.dp,
  val pageHeaderHeight: Dp = 52.dp,
  val pageHeaderPadding: Dp = 16.dp,
  val tabRowHeight: Dp = 40.dp,
  val tableHeaderHeight: Dp = 28.dp,
  val tableRowCompact: Dp = 32.dp,
  val tableGroupHeaderHeight: Dp = 24.dp,
  val pulseBarHeight: Dp = 32.dp,
  val inspectorWidth: Dp = 320.dp,
  val inspectorMinWidth: Dp = 280.dp,
  val inspectorMaxWidth: Dp = 480.dp,
  val iconLabelGap: Dp = 8.dp,
  val rowPadding: Dp = 12.dp,
  val cellPadding: Dp = 8.dp,
  val sectionGap: Dp = 24.dp,
) {
  @Deprecated("Use s0_5.", ReplaceWith("s0_5"))
  val xxs: Dp get() = s0_5

  @Deprecated("Use s1.", ReplaceWith("s1"))
  val xs: Dp get() = s1

  @Deprecated("Off the 4-point grid; use s1 or s2.")
  val sm: Dp get() = 6.dp

  @Deprecated("Use s2.", ReplaceWith("s2"))
  val md: Dp get() = s2

  @Deprecated("Use s3.", ReplaceWith("s3"))
  val lg: Dp get() = s3

  @Deprecated("Use s4.", ReplaceWith("s4"))
  val xl: Dp get() = s4

  @Deprecated("Use s5.", ReplaceWith("s5"))
  val xxl: Dp get() = s5

  @Deprecated("Use s6.", ReplaceWith("s6"))
  val xxxl: Dp get() = s6

  @Deprecated("Use s8.", ReplaceWith("s8"))
  val x4l: Dp get() = s8

  @Deprecated("Use s10.", ReplaceWith("s10"))
  val x5l: Dp get() = s10

  @Deprecated("Use s16.", ReplaceWith("s16"))
  val x6l: Dp get() = s16
}

/** The spacing scale. */
fun ketchSpacing(): KetchSpacing = KetchSpacing()
