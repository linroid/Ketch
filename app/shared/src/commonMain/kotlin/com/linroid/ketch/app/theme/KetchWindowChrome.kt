package com.linroid.ketch.app.theme

import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max

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
  /**
   * The same room in content drawn at [zoom] times its normal size. The window's controls keep
   * their size, so they cover 1/[zoom] as much of the zoomed content across; the title zone keeps
   * at least its height, as the buttons the content puts in it grow with the zoom.
   */
  fun zoomed(zoom: Float): WindowChrome =
    WindowChrome(top = max(top, top / zoom), leading = leading / zoom)

  companion object {
    /** The window draws its own title bar, or there is no window. */
    val None: WindowChrome = WindowChrome(top = 0.dp, leading = 0.dp)
  }
}

/** Window chrome of the window below, provided by `KetchTheme`. */
val LocalWindowChrome = staticCompositionLocalOf { WindowChrome.None }
