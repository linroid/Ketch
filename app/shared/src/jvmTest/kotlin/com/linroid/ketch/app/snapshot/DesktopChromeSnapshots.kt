package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.App
import com.linroid.ketch.app.platform.DesktopHooks
import com.linroid.ketch.app.platform.DetectedBrowser
import com.linroid.ketch.app.platform.IntegrationStatus
import com.linroid.ketch.app.platform.LocalDesktopHooks
import com.linroid.ketch.app.platform.LocalIntegrationStatus
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.LocalWindowChrome
import com.linroid.ketch.app.theme.WindowChrome
import com.linroid.ketch.app.ui.settings.SettingsHost
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The desktop window chrome: the app under a transparent macOS title bar, with the traffic
 * lights drawn where AWT puts them, and the Settings window's content at its default size. The
 * desktop app provides the same [LocalWindowChrome] and [LocalIntegrationStatus]; see
 * [SnapshotHarness] for how to run it.
 */
class DesktopChromeSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun macChrome_desktopSizes_leavesRoomForTheTrafficLights() {
    val sizes = listOf(
      SnapshotSize.Desktop,
      SnapshotSize.SmallDesktop,
      SnapshotSize(840.dp, 720.dp, KetchDensity.Compact),
      SnapshotSize.Medium,
      MinMainWindowSize,
    )
    for (size in sizes) {
      for (theme in SnapshotTheme.entries) {
        chromeSnapshot("mac-chrome", size, theme) { controller -> MacWindow(controller) }
      }
    }
  }

  @Test
  fun macChrome_noDownloads_showsTheBrowserStatus() {
    for (theme in SnapshotTheme.entries) {
      chromeSnapshot("mac-chrome-empty", SnapshotSize.Desktop, theme, SampleData.empty()) {
        MacWindow(it)
      }
    }
  }

  @Test
  fun settingsWindow_defaultSize_showsSettingsInTheWindow() {
    val pages = listOf(SettingsTarget.Page.General, SettingsTarget.Page.Integration)
    for (page in pages) {
      for (theme in SnapshotTheme.entries) {
        val name = "settings-window-${page.name.lowercase()}"
        chromeSnapshot(name, SettingsWindowSize, theme) { controller ->
          SettingsWindowContent(controller, SettingsTarget(page), theme)
        }
      }
    }
  }

  @Test
  fun settingsWindow_minimumSize_keepsSettingsInTheWindow() {
    for (theme in SnapshotTheme.entries) {
      chromeSnapshot("settings-window-general", MinSettingsWindowSize, theme) { controller ->
        SettingsWindowContent(controller, SettingsTarget(SettingsTarget.Page.General), theme)
      }
    }
  }
}

/** The Settings window's size until the user resizes it, as the desktop app opens it. */
private val SettingsWindowSize = SnapshotSize(860.dp, 640.dp, KetchDensity.Compact)

/** The smallest the Settings window gets. */
private val MinSettingsWindowSize = SnapshotSize(640.dp, 480.dp, KetchDensity.Compact)

/** The smallest the main window gets. */
private val MinMainWindowSize = SnapshotSize(720.dp, 480.dp, KetchDensity.Compact)

/** What the desktop app reports while the extension is set up in Chrome only. */
internal val SampleIntegration = IntegrationStatus(
  browsers = listOf(
    DetectedBrowser("Chrome", extensionConnected = true),
    DetectedBrowser("Edge"),
    DetectedBrowser("Firefox"),
  ),
  extensionConnected = true,
  magnetHandler = true,
)

/** Desktop hooks that do nothing, so the pages show their desktop rows. */
internal val DesktopHooksShown = object : DesktopHooks {
  override val isSupported: Boolean get() = true
}

/** The main window on macOS: the app filling it, under the title bar's traffic lights. */
@Composable
internal fun MacWindow(controller: AppController) {
  CompositionLocalProvider(
    LocalWindowChrome provides WindowChrome(top = 28.dp, leading = 78.dp),
    LocalIntegrationStatus provides SampleIntegration,
  ) {
    Box(Modifier.fillMaxSize()) {
      App(controller)
      TrafficLights()
    }
  }
}

/** The Settings window's content, as `SettingsWindow` in the desktop app lays it out. */
@Composable
private fun SettingsWindowContent(
  controller: AppController,
  target: SettingsTarget,
  theme: SnapshotTheme,
) {
  val appSettings = controller.appSettings
  CompositionLocalProvider(
    LocalAppState provides controller.state,
    LocalDesktopHooks provides DesktopHooksShown,
    LocalIntegrationStatus provides SampleIntegration,
  ) {
    KetchTheme(
      darkTheme = theme == SnapshotTheme.Dark,
      accent = appSettings.accent,
      density = appSettings.ui.density,
      reduceMotion = true,
    ) {
      Box(Modifier.fillMaxSize().background(KetchTheme.colors.canvas)) {
        SettingsHost(controller.state, target, onClose = {})
      }
    }
  }
}

/** Close, minimize and zoom, 12 dp circles 20 dp apart in the 28 dp title bar row. */
@Composable
private fun TrafficLights() {
  Canvas(Modifier.size(78.dp, 28.dp)) {
    val radius = 6.dp.toPx()
    TrafficLightColors.forEachIndexed { index, color ->
      val center = Offset((14 + index * 20).dp.toPx(), 14.dp.toPx())
      drawCircle(color, radius, center)
      drawCircle(Color.Black.copy(alpha = 0.12f), radius, center, style = Stroke(1f))
    }
  }
}

/** The macOS title bar's close, minimize and zoom buttons. */
internal val TrafficLightColors = listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840))

/**
 * Renders [content] over the app of [data], as [appSnapshot] renders the app root, to
 * `<name>-<theme>-<width>x<height>.png`.
 */
private fun chromeSnapshot(
  name: String,
  size: SnapshotSize,
  theme: SnapshotTheme,
  data: SampleData = SampleData.downloads(),
  content: @Composable (AppController) -> Unit,
): File = withSample(theme, size.density.toMode(), data) { env ->
  SnapshotHarness.capture("$name-${theme.id}-${size.id}", size) { content(env.controller) }
}
