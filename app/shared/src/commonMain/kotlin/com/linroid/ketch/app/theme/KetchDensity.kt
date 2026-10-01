package com.linroid.ketch.app.theme

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.config.DensityMode

/**
 * How dense the UI is: [Compact] for a pointer, [Comfortable] for touch.
 *
 * Each value carries the control sizes of that density.
 *
 * @property buttonSmall height of small buttons.
 * @property buttonMedium height of medium buttons, the default.
 * @property buttonLarge height of large buttons.
 * @property iconButton visual size of icon buttons.
 * @property iconButtonTarget hit area of icon buttons.
 * @property controlGlyph glyph size in controls.
 * @property navGlyph glyph size in navigation.
 * @property input height of single-line inputs.
 * @property chip height of chips, segmented controls and tabs.
 * @property sidebarItem height of sidebar items.
 * @property deviceRow height of device rows.
 * @property tableRow height of table rows.
 * @property listRow height of two-line list rows.
 * @property listRowWithLanes height of list rows that show lanes.
 * @property menuItem height of menu items.
 * @property pagePadding padding of card pages such as Devices and Settings.
 */
enum class KetchDensity(
  val buttonSmall: Dp,
  val buttonMedium: Dp,
  val buttonLarge: Dp,
  val iconButton: Dp,
  val iconButtonTarget: Dp,
  val controlGlyph: Dp,
  val navGlyph: Dp,
  val input: Dp,
  val chip: Dp,
  val sidebarItem: Dp,
  val deviceRow: Dp,
  val tableRow: Dp,
  val listRow: Dp,
  val listRowWithLanes: Dp,
  val menuItem: Dp,
  val pagePadding: Dp,
) {
  Compact(
    buttonSmall = 28.dp,
    buttonMedium = 32.dp,
    buttonLarge = 36.dp,
    iconButton = 28.dp,
    iconButtonTarget = 32.dp,
    controlGlyph = 16.dp,
    navGlyph = 18.dp,
    input = 32.dp,
    chip = 28.dp,
    sidebarItem = 32.dp,
    deviceRow = 36.dp,
    tableRow = 36.dp,
    listRow = 56.dp,
    listRowWithLanes = 56.dp,
    menuItem = 28.dp,
    pagePadding = 24.dp,
  ),
  Comfortable(
    buttonSmall = 36.dp,
    buttonMedium = 44.dp,
    buttonLarge = 48.dp,
    iconButton = 40.dp,
    iconButtonTarget = 48.dp,
    controlGlyph = 20.dp,
    navGlyph = 24.dp,
    input = 48.dp,
    chip = 32.dp,
    sidebarItem = 40.dp,
    deviceRow = 48.dp,
    tableRow = 44.dp,
    listRow = 64.dp,
    listRowWithLanes = 72.dp,
    menuItem = 48.dp,
    pagePadding = 16.dp,
  ),
}

/** Density of the UI below, provided by `KetchTheme`. */
val LocalKetchDensity = staticCompositionLocalOf { KetchDensity.Compact }

/** The density [mode] asks for, with [auto] standing in for [DensityMode.Auto]. */
internal fun DensityMode.resolve(auto: KetchDensity): KetchDensity = when (this) {
  DensityMode.Auto -> auto
  DensityMode.Compact -> KetchDensity.Compact
  DensityMode.Comfortable -> KetchDensity.Comfortable
}

/**
 * Hosts [content] at the density [mode] picks.
 *
 * Desktop and the web are pointer-first, so Auto is [KetchDensity.Compact] there. Phones and
 * tablets start [KetchDensity.Comfortable] and follow the latest pointer: a mouse or trackpad
 * makes them compact until the next touch.
 */
@Composable
internal fun DensityHost(mode: DensityMode, content: @Composable (KetchDensity) -> Unit) {
  if (!isMobilePlatform) {
    content(mode.resolve(KetchDensity.Compact))
    return
  }
  var pointer by remember { mutableStateOf(PointerType.Touch) }
  val auto = if (pointer == PointerType.Mouse) KetchDensity.Compact else KetchDensity.Comfortable
  // The tracker is always in place, so changing the setting keeps the state of the content.
  Box(
    modifier = Modifier.pointerInput(Unit) {
      awaitPointerEventScope {
        while (true) {
          val event = awaitPointerEvent(PointerEventPass.Initial)
          val type = event.changes.firstOrNull()?.type ?: continue
          if (type == PointerType.Mouse || type == PointerType.Touch) pointer = type
        }
      }
    },
    propagateMinConstraints = true,
  ) {
    content(mode.resolve(auto))
  }
}
