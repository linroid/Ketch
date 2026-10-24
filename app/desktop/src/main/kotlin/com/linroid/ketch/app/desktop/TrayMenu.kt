package com.linroid.ketch.app.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.awt.SwingWindow
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowDecoration
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.rememberWindowState
import com.linroid.ketch.app.components.KetchContextMenu
import com.linroid.ketch.app.components.KetchMenuScope
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.platform.rememberReduceMotion
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.isDark
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import java.awt.GraphicsEnvironment
import java.awt.MouseInfo
import java.awt.Rectangle
import java.awt.Toolkit
import java.awt.Window
import kotlin.math.roundToInt

/**
 * A screen, in the coordinates AWT places windows in.
 *
 * @property bounds the whole screen.
 * @property workArea the part windows may take: [bounds] without the taskbar and other docked
 *   bars.
 */
internal data class TrayScreen(val bounds: IntRect, val workArea: IntRect)

/**
 * Where the tray menu opens: at [pointer], within [area], the work area of the screen it is on,
 * both in the coordinates AWT places windows in.
 *
 * Each request is its own menu, so it compares by identity: a right click that opens the menu
 * again at the same place is a new one.
 */
internal class TrayMenuRequest(val pointer: IntOffset, val area: IntRect)

/**
 * The tray menu's request for a right click at [pointer] on [screens], or `null` when there is no
 * screen to show it on.
 */
internal fun trayMenuRequest(pointer: IntOffset, screens: List<TrayScreen>): TrayMenuRequest? {
  val area = workAreaAt(pointer, screens) ?: return null
  return TrayMenuRequest(pointer, area)
}

/**
 * The work area of the screen [point] is on, else of the screen nearest it, as when the pointer
 * sits on a taskbar AWT counts on neither screen; `null` without screens.
 */
internal fun workAreaAt(point: IntOffset, screens: List<TrayScreen>): IntRect? {
  val screen = screens.firstOrNull { it.bounds.containsPoint(point) }
    ?: screens.minByOrNull { it.bounds.distanceSquaredTo(point) }
  return screen?.workArea?.takeUnless { it.isEmpty }
}

private fun IntRect.containsPoint(point: IntOffset): Boolean =
  point.x in left..<right && point.y in top..<bottom

private fun IntRect.distanceSquaredTo(point: IntOffset): Long {
  val dx = (maxOf(left - point.x, 0, point.x - (right - 1))).toLong()
  val dy = (maxOf(top - point.y, 0, point.y - (bottom - 1))).toLong()
  return dx * dx + dy * dy
}

/** Every screen of this computer, as AWT reports them. */
internal fun systemScreens(): List<TrayScreen> {
  if (GraphicsEnvironment.isHeadless()) return emptyList()
  val toolkit = Toolkit.getDefaultToolkit()
  return GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices.map { device ->
    val configuration = device.defaultConfiguration
    val bounds = configuration.bounds
    val insets = toolkit.getScreenInsets(configuration)
    val workArea = Rectangle(
      bounds.x + insets.left,
      bounds.y + insets.top,
      bounds.width - insets.left - insets.right,
      bounds.height - insets.top - insets.bottom,
    )
    TrayScreen(bounds.toIntRect(), workArea.toIntRect())
  }
}

/**
 * Where the pointer is on the screen, else [fallback], such as where a tray icon's mouse event
 * says it was.
 */
internal fun pointerOnScreen(fallback: IntOffset): IntOffset {
  val location = MouseInfo.getPointerInfo()?.location ?: return fallback
  return IntOffset(location.x, location.y)
}

private fun Rectangle.toIntRect() = IntRect(x, y, x + width, y + height)

/**
 * The tray menu Ketch draws itself on Windows, in the app's font, colors and zoom: a window as
 * large as the work area of the screen of [request], always on top, transparent but for the menu
 * that opens at the pointer, so its submenus have room to open beside it. It stays off the
 * taskbar and Alt+Tab.
 *
 * The menu closes, calling [onDismiss], when an item is chosen, on Esc, on a click outside it or
 * when the window loses the focus, as when another app or the taskbar is clicked.
 *
 * @param title what screen readers call the window.
 * @param content the menu's entries.
 */
@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun TrayMenuWindow(
  request: TrayMenuRequest,
  appSettings: AppSettingsController,
  title: String,
  onDismiss: () -> Unit,
  content: KetchMenuScope.() -> Unit,
) {
  val area = request.area
  val state = rememberWindowState(
    position = WindowPosition(area.left.dp, area.top.dp),
    size = DpSize(area.width.dp, area.height.dp),
  )
  val currentOnDismiss by rememberUpdatedState(onDismiss)
  SwingWindow(
    onCloseRequest = onDismiss,
    state = state,
    title = title,
    decoration = WindowDecoration.Undecorated(),
    transparent = true,
    resizable = false,
    alwaysOnTop = true,
    init = { it.type = Window.Type.UTILITY },
  ) {
    LaunchedEffect(Unit) { bringToFront(window) }
    val windowInfo = LocalWindowInfo.current
    LaunchedEffect(windowInfo) {
      // Until it first has the focus, the window has none to lose.
      snapshotFlow { windowInfo.isWindowFocused }.dropWhile { !it }.first { !it }
      currentOnDismiss()
    }
    // The window starts at the area's top left corner, at the scale of its screen.
    val scale = LocalDensity.current.density
    val pointer = IntOffset(
      ((request.pointer.x - area.left) * scale).roundToInt(),
      ((request.pointer.y - area.top) * scale).roundToInt(),
    )
    TrayMenuTheme(appSettings) {
      TrayMenuContent(pointer, onDismiss, content = content)
    }
  }
}

/** The app's theme, colors, density and zoom as its settings choose them, for the tray menu. */
@Composable
internal fun TrayMenuTheme(appSettings: AppSettingsController, content: @Composable () -> Unit) {
  val systemReducesMotion = rememberReduceMotion()
  KetchTheme(
    darkTheme = appSettings.themeMode.isDark(),
    accent = appSettings.accent,
    density = appSettings.ui.density,
    reduceMotion = appSettings.ui.reduceMotion || systemReducesMotion,
    zoom = appSettings.zoomScale,
    content = content,
  )
}

/**
 * The tray menu, opened at [pointer], in pixels from the top left corner of the layout it fills,
 * as system context menus open: with a taskbar below the layout, just above it.
 */
@Composable
internal fun TrayMenuContent(
  pointer: IntOffset,
  onDismiss: () -> Unit,
  modifier: Modifier = Modifier,
  content: KetchMenuScope.() -> Unit,
) {
  Box(modifier.fillMaxSize()) {
    KetchContextMenu(position = pointer, onDismissRequest = onDismiss, content = content)
  }
}

/**
 * Adds [entries] to a Ketch menu, each item calling [onAction]: the menu bar and tray model drawn
 * as the app's own menus are, with the glyphs of the commands they run. Lines of text one after
 * another, such as the tray's status sentence and speed, form one block.
 */
internal fun KetchMenuScope.menuEntries(
  entries: List<MenuEntry>,
  platform: KeyboardPlatform,
  onAction: (MenuAction) -> Unit,
) {
  var index = 0
  while (index < entries.size) {
    when (val entry = entries[index]) {
      MenuEntry.Separator -> divider()
      is MenuEntry.Header -> {
        val lines = entries.subList(index, entries.size)
          .takeWhile { it is MenuEntry.Header }
          .map { (it as MenuEntry.Header).text }
        custom { MenuTextLines(lines) }
        index += lines.size
        continue
      }
      is MenuEntry.Item -> item(
        label = entry.label,
        onClick = { onAction(entry.action) },
        icon = entry.action.icon,
        shortcut = entry.shortcut?.label(platform),
        enabled = entry.enabled,
        checked = entry.checked,
      )
      is MenuEntry.Submenu -> submenu(
        label = entry.label,
        icon = entry.icon,
        enabled = entry.enabled,
      ) {
        menuEntries(entry.entries, platform, onAction)
      }
    }
    index++
  }
}

// The glyph of the command an action runs, for the actions that have one.
private val MenuAction.icon: KetchIcon?
  get() = when (this) {
    is MenuAction.Run -> command.icon
    is MenuAction.PauseAll -> KetchCommands.PauseAll.icon
    is MenuAction.ResumeAll -> KetchCommands.ResumeAll.icon
    is MenuAction.RetryFailed -> KetchCommands.RetryFailed.icon
    is MenuAction.AddClipboardLink -> KetchCommands.AddClipboardLink.icon
    else -> null
  }

// Speed menus take the slow lane's glyph and the Devices menu that of the app's Devices page.
private val MenuEntry.Submenu.icon: KetchIcon?
  get() {
    val actions = entries.mapNotNull { (it as? MenuEntry.Item)?.action }
    return when {
      actions.any { it is MenuAction.SetSpeedMode || it is MenuAction.SetSpeedLimit } -> {
        KetchCommands.SlowLane.icon
      }
      MenuAction.AddDevice in actions -> KetchCommands.Devices.icon
      else -> null
    }
  }

/** Lines of text in a menu, such as the tray's status: the first as a label, the rest quieter. */
@Composable
private fun MenuTextLines(lines: List<UiText>) {
  val colors = KetchTheme.colors
  Column {
    lines.forEachIndexed { index, line ->
      val style = if (index == 0) KetchTheme.typography.label else KetchTheme.typography.labelS
      val color = if (index == 0) colors.textPrimary else colors.textSecondary
      BasicText(text = line.resolve(), style = style.copy(color = color))
    }
  }
}
