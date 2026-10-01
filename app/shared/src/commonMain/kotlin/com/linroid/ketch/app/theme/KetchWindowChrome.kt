package com.linroid.ketch.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Space the window's own controls take inside the app's content, such as the macOS traffic
 * lights over a full-window layout.
 *
 * @property top height of the title zone at the top of the window.
 * @property leading width taken by the window controls at the start of the title zone.
 */
@Immutable
data class WindowChrome(
  val top: Dp,
  val leading: Dp,
) {
  companion object {
    /** The window draws its own title bar, or there is no window. */
    val None: WindowChrome = WindowChrome(top = 0.dp, leading = 0.dp)
  }
}

/** Window chrome of the window below, provided by `KetchTheme`. */
val LocalWindowChrome = staticCompositionLocalOf { WindowChrome.None }
