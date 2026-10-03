package com.linroid.ketch.app.desktop

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshots.ObserverHandle
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.window.FrameWindowScope
import androidx.compose.ui.window.MenuBar
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowState
import com.linroid.ketch.app.components.LocalDeviceTypes
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.input.ShortcutContext
import com.linroid.ketch.app.input.ShortcutMatcher
import com.linroid.ketch.app.log.FileLogger
import com.linroid.ketch.app.platform.DesktopHooks
import com.linroid.ketch.app.platform.LocalDesktopHooks
import com.linroid.ketch.app.platform.LocalIntegrationStatus
import com.linroid.ketch.app.platform.rememberReduceMotion
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.isDark
import com.linroid.ketch.app.ui.devices.rememberDeviceTypes
import com.linroid.ketch.app.ui.settings.LocalFileLogger
import com.linroid.ketch.app.ui.settings.SettingsHost
import ketch.app.desktop.generated.resources.Res
import ketch.app.desktop.generated.resources.menu_file
import ketch.app.desktop.generated.resources.menu_window
import ketch.app.desktop.generated.resources.settings_window_title
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.withContext
import org.jetbrains.compose.resources.stringResource
import java.awt.Dimension

/**
 * The Settings window: a window of its own, not modal, so the main window keeps showing
 * downloads while settings change. It opens for the app's Settings requests
 * ([AppState.openSettings]), which [claimRequests] takes before the main window sees them, and
 * comes to the front when asked again while it shows.
 *
 * @param takeRequest returns the pending Settings request, if any, and clears it.
 */
@Stable
internal class SettingsWindowState(private val takeRequest: () -> SettingsTarget?) {
  /** Page asked for last, or `null` while the window is closed. */
  var target: SettingsTarget? by mutableStateOf(null)
    private set

  /** Whether the window is open. */
  val isOpen: Boolean get() = target != null

  /** Whether the window has focus, which counts as Ketch being in front. */
  var focused: Boolean by mutableStateOf(false)

  private val fronts = MutableSharedFlow<Unit>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )

  /** Emits when the window should come to the front. */
  val frontRequests: SharedFlow<Unit> = fronts.asSharedFlow()

  /** Opens the window at [target], or brings it to the front there. */
  fun open(target: SettingsTarget) {
    this.target = target
    fronts.tryEmit(Unit)
  }

  /** Opens the window at General, or brings it to the front at the page it shows. */
  fun show() {
    open(target ?: SettingsTarget(SettingsTarget.Page.General))
  }

  /** Closes the window. */
  fun close() {
    target = null
    focused = false
  }

  /**
   * Opens the window for each Settings request from now on, taking it as soon as the state
   * change applies. The main window's shell, which shows Settings itself on other platforms,
   * recomposes only after that, so it never sees the request.
   *
   * @return what stops taking requests.
   */
  fun claimRequests(): ObserverHandle {
    claim()
    return Snapshot.registerApplyObserver { _, _ -> claim() }
  }

  private fun claim() {
    takeRequest()?.let(::open)
  }
}

/** Settings window state for [state]'s requests; see [SettingsWindowState]. */
internal fun SettingsWindowState(state: AppState): SettingsWindowState =
  SettingsWindowState(takeRequest = { state.settingsRequest?.also { state.closeSettings() } })

/** Size of the Settings window, width by height, until the user resizes it. */
internal val SettingsWindowSize = 860 to 640

/** Smallest size of the Settings window, width by height. */
internal val MinSettingsWindowSize = 640 to 480

/**
 * The Settings window of [settings], showing [SettingsHost] for [controller] while it is open.
 * Esc, ⌘W (Ctrl+W elsewhere) and the close button close it; Ctrl+Q quits on Windows and Linux,
 * which have no menu bar to own it.
 *
 * On macOS it has a menu bar of its own, whose Close Window and Minimize own ⌘W and ⌘M here.
 * Without one, macOS would show the menu bar Ketch keeps for when no window is open, where ⌘W
 * closes the main window and ⌘F searches the downloads.
 *
 * @param windowState where the window is, kept while it is closed so it reopens there.
 * @param icon the app's icon.
 * @param hooks the desktop's [DesktopHooks], for the pages that change how Ketch runs.
 * @param integration the browsers and default apps the Integration page shows; asked again
 *   whenever the window comes to the front.
 * @param fileLogger the app's log files, which the About page opens.
 * @param onQuit quits Ketch.
 */
@Composable
internal fun SettingsWindow(
  settings: SettingsWindowState,
  windowState: WindowState,
  icon: Painter,
  controller: AppController,
  hooks: DesktopHooks,
  integration: DesktopIntegrationStatus,
  fileLogger: FileLogger?,
  onQuit: () -> Unit,
) {
  val target = settings.target ?: return
  // Text resolves as it composes, so the window composes again in a new language.
  val language = controller.appSettings.language
  Window(
    onCloseRequest = settings::close,
    state = windowState,
    title = stringResource(Res.string.settings_window_title),
    icon = icon,
    onPreviewKeyEvent = { event ->
      val command = settingsShortcuts?.match(event, ShortcutContext())
      when (command) {
        KetchCommands.CloseWindow -> settings.close()
        KetchCommands.Quit -> onQuit()
      }
      command != null
    },
    // After the page had the chance, so Esc first closes a menu or leaves a field.
    onKeyEvent = { event ->
      val escape = event.type == KeyEventType.KeyDown && event.key == Key.Escape
      if (escape) settings.close()
      escape
    },
  ) {
    LaunchedEffect(Unit) {
      val (minWidth, minHeight) = MinSettingsWindowSize
      window.minimumSize = Dimension(minWidth, minHeight)
      windowState.isMinimized = false
      bringToFront(window)
    }
    LaunchedEffect(settings) {
      settings.frontRequests.collect {
        // Coming to the front does not bring a window back from the Dock.
        windowState.isMinimized = false
        bringToFront(window)
      }
    }
    key(language) {
      SettingsMenuBar(onClose = settings::close, onMinimize = { windowState.isMinimized = true })
    }
    val focused = LocalWindowInfo.current.isWindowFocused
    SideEffect { settings.focused = focused }
    // Browsers and default apps may have changed while Ketch was in the background.
    LaunchedEffect(focused) {
      if (focused) withContext(Dispatchers.IO) { integration.refresh() }
    }
    val appSettings = controller.appSettings
    val darkTheme = appSettings.themeMode.isDark()
    MacTitleBar(fullWindowContent = false, darkTheme = darkTheme)
    val systemReducesMotion = rememberReduceMotion()
    CompositionLocalProvider(
      LocalAppState provides controller.state,
      LocalClock provides controller.state.clock,
      LocalFileLogger provides fileLogger,
      LocalDesktopHooks provides hooks,
      LocalIntegrationStatus provides integration.status,
      LocalDeviceTypes provides rememberDeviceTypes(controller.state),
    ) {
      KetchTheme(
        darkTheme = darkTheme,
        accent = appSettings.accent,
        density = appSettings.ui.density,
        reduceMotion = appSettings.ui.reduceMotion || systemReducesMotion,
      ) {
        Box(Modifier.fillMaxSize().background(KetchTheme.colors.canvas)) {
          key(language) { SettingsHost(controller.state, target, onClose = settings::close) }
        }
      }
    }
  }
}

/** The Settings window's macOS menu bar: Close Window and Minimize. */
@Composable
private fun FrameWindowScope.SettingsMenuBar(onClose: () -> Unit, onMinimize: () -> Unit) {
  if (DesktopOs.current != DesktopOs.MAC) return
  val platform = KeyboardPlatform.Mac
  val onAction: (MenuAction) -> Unit = { action ->
    when ((action as? MenuAction.Run)?.command) {
      KetchCommands.CloseWindow -> onClose()
      KetchCommands.Minimize -> onMinimize()
    }
  }
  MenuBar {
    Menu(stringResource(Res.string.menu_file)) {
      MenuEntries(listOf(commandItem(KetchCommands.CloseWindow, platform)), platform, onAction)
    }
    Menu(stringResource(Res.string.menu_window)) {
      MenuEntries(listOf(commandItem(KetchCommands.Minimize, platform)), platform, onAction)
    }
  }
}

// Closing the window and quitting on Windows and Linux, which have no menu bar to own them; on
// macOS the menu bars do.
private val settingsShortcuts: ShortcutMatcher? = if (DesktopOs.current == DesktopOs.MAC) {
  null
} else {
  ShortcutMatcher(commands = listOf(KetchCommands.CloseWindow, KetchCommands.Quit))
}
