package com.linroid.ketch.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.awtEventOrNull
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowState
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.PulseState
import com.linroid.ketch.app.theme.KetchSpacing
import com.linroid.ketch.app.theme.WindowChrome
import ketch.app.desktop.generated.resources.Res
import ketch.app.desktop.generated.resources.app_title_status
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * System property that keeps the system's title bar on macOS, in case the full-window layout
 * misbehaves on some Java runtime: `-Dketch.decoratedWindow=true`.
 */
internal const val DECORATED_WINDOW_PROPERTY = "ketch.decoratedWindow"

/**
 * Whether the main window's content fills it under a transparent title bar, with the traffic
 * lights over the sidebar: on macOS, unless [decorated] asks for the system's title bar. Windows
 * and Linux keep their native frames.
 */
internal fun usesFullWindowContent(
  os: DesktopOs = DesktopOs.current,
  decorated: Boolean = System.getProperty(DECORATED_WINDOW_PROPERTY).toBoolean(),
): Boolean = os == DesktopOs.MAC && !decorated

/**
 * Room the title bar takes inside a window whose content fills it: the row of the traffic lights
 * and the width they cover. Full screen hides the title bar, so it takes none there.
 *
 * @param macVersion the macOS version, such as "26.1", which decides how tall the row is.
 */
internal fun windowChrome(
  fullWindowContent: Boolean,
  placement: WindowPlacement,
  macVersion: String = System.getProperty("os.version").orEmpty(),
): WindowChrome =
  if (fullWindowContent && placement != WindowPlacement.Fullscreen) {
    WindowChrome(top = titleBarHeight(macVersion), leading = TrafficLightsWidth)
  } else {
    WindowChrome.None
  }

// macOS 26 made the title bar taller, so its traffic lights sit lower; the buttons beside them
// are centred on the same line.
private fun titleBarHeight(macVersion: String): Dp {
  val major = macVersion.substringBefore('.').toIntOrNull() ?: 0
  return if (major >= 26) 32.dp else 28.dp
}

/** The `apple.awt.windowAppearance` that draws the title bar in the dark or the light theme. */
internal fun windowAppearance(darkTheme: Boolean): String =
  if (darkTheme) "NSAppearanceNameDarkAqua" else "NSAppearanceNameAqua"

/** The main window's title: "Ketch — 3 downloading · 45%" while downloading, else "Ketch". */
internal fun windowTitle(pulse: PulseState): UiText =
  pulse.shortSentence()?.let { Res.string.app_title_status.text(it) } ?: verbatim("Ketch")

/**
 * Sets up this window's macOS title bar: with [fullWindowContent] the content fills the window
 * under a transparent title bar that hides the title, and the title bar follows [darkTheme]
 * either way. Does nothing on Windows and Linux.
 *
 * AWT applies these client properties as they change, so the window may already be showing.
 */
@Composable
internal fun FrameWindowScope.MacTitleBar(fullWindowContent: Boolean, darkTheme: Boolean) {
  if (DesktopOs.current != DesktopOs.MAC) return
  val rootPane = window.rootPane
  SideEffect {
    if (fullWindowContent) {
      rootPane.putClientProperty("apple.awt.fullWindowContent", true)
      rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
      rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    }
    rootPane.putClientProperty("apple.awt.windowAppearance", windowAppearance(darkTheme))
  }
}

/**
 * Lays [content] over the top [TitleBarHeight] of the window, which acts as a title bar wherever
 * [content] has nothing to click: dragging it moves the window, and double-clicking it does what
 * System Settings says a title bar does, zooming the window [state] by default. Full screen has
 * no title bar to stand in for.
 */
@Composable
internal fun FrameWindowScope.TitleBarArea(state: WindowState, content: @Composable () -> Unit) {
  val scope = rememberCoroutineScope()
  val onDoubleClick: () -> Unit = remember(state, scope) {
    { scope.launch { titleBarDoubleClicked(state) } }
  }
  val fullScreen = state.placement == WindowPlacement.Fullscreen
  TitleBarLayout(
    height = TitleBarHeight,
    titleBar = { modifier ->
      if (!fullScreen) WindowDraggableArea(modifier.onDoubleClick(onDoubleClick))
    },
    content = content,
  )
}

/**
 * Puts [titleBar], [height] tall, under [content]. A press reaches the title bar only where no
 * part of [content] takes pointer input, so buttons and fields above it keep working.
 */
@Composable
internal fun TitleBarLayout(
  height: Dp,
  titleBar: @Composable (Modifier) -> Unit,
  content: @Composable () -> Unit,
) {
  Box(Modifier.fillMaxSize()) {
    titleBar(Modifier.fillMaxWidth().height(height))
    content()
  }
}

/** Runs [action] on a double click with the primary button, as the system counts clicks. */
internal fun Modifier.onDoubleClick(action: () -> Unit): Modifier = pointerInput(action) {
  awaitPointerEventScope {
    while (true) {
      val event = awaitPointerEvent()
      val clicks = event.awtEventOrNull?.clickCount ?: 0
      if (event.type == PointerEventType.Press && event.buttons.isPrimaryPressed && clicks == 2) {
        action()
      }
    }
  }
}

/** What double-clicking a title bar does, as set in System Settings › Desktop & Dock. */
internal enum class TitleBarAction {
  Zoom,
  Minimize,
  None,
}

/**
 * The [TitleBarAction] for macOS's `AppleActionOnDoubleClick` [setting]: `Minimize`, `None`, or
 * one that zooms (`Maximize`, `Fill`, or none set).
 */
internal fun titleBarAction(setting: String?): TitleBarAction = when (setting?.trim()) {
  "Minimize" -> TitleBarAction.Minimize
  "None" -> TitleBarAction.None
  else -> TitleBarAction.Zoom
}

// Read on each double click, so a change in System Settings applies at once.
private suspend fun titleBarDoubleClicked(state: WindowState) {
  val setting = withContext(Dispatchers.IO) {
    runForOutput(listOf("defaults", "read", "-g", "AppleActionOnDoubleClick"))
  }
  when (titleBarAction(setting)) {
    TitleBarAction.Zoom -> state.placement = if (state.placement == WindowPlacement.Maximized) {
      WindowPlacement.Floating
    } else {
      WindowPlacement.Maximized
    }
    TitleBarAction.Minimize -> state.isMinimized = true
    TitleBarAction.None -> Unit
  }
}

// The width the traffic lights and their margin cover.
private val TrafficLightsWidth = 78.dp

// The sidebar's title zone and, beside it, the page header below the content card's inset.
private val TitleBarHeight: Dp = KetchSpacing().let { it.cardInset + it.pageHeaderHeight }
