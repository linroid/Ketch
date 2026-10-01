package com.linroid.ketch.app.snapshot

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.input.key.Key
import com.linroid.ketch.app.App
import com.linroid.ketch.app.platform.rememberFilePicker
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.palette.CommandPalette
import com.linroid.ketch.app.ui.palette.PaletteHistory
import com.linroid.ketch.app.ui.shell.ShellCommands
import com.linroid.ketch.app.ui.shell.ShellState
import com.linroid.ketch.config.DensityMode
import kotlinx.coroutines.runBlocking
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The command palette (`⌘K`) over the app: what it lists before anything is typed, a pasted
 * link with a row per device, a typed speed, a device command, downloads found by name and by
 * search token, the Discover fallback, no matches, and the highlight moved by the keyboard; see
 * [SnapshotHarness] for how to run it.
 *
 * The app shows the sample's downloads on this Mac, with the NAS connected, the Slow lane on
 * and Discover set up ([SettingsEnvironment]).
 */
class PaletteSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun empty_everySizeAndTheme_listsRecentThenCommands() {
    for (size in SIZES) {
      for (theme in SnapshotTheme.entries) {
        paletteSnapshot("palette-empty", size, theme, recent = RECENT)
      }
    }
  }

  @Test
  fun link_everySize_downloadsOnEachDevice() {
    for (size in SIZES) {
      for (theme in SnapshotTheme.entries) {
        paletteSnapshot("palette-link", size, theme, query = LINK)
      }
    }
  }

  @Test
  fun speed_desktop_setsTheSlowLane() {
    for (theme in SnapshotTheme.entries) {
      paletteSnapshot("palette-speed", SnapshotSize.Desktop, theme, query = "5m")
    }
  }

  @Test
  fun deviceCommand_desktop_findsTheNas() {
    for (theme in SnapshotTheme.entries) {
      paletteSnapshot("palette-pause-nas", SnapshotSize.Desktop, theme, query = "pause nas")
    }
  }

  @Test
  fun downloads_desktopAndPhone_findTasksByName() {
    for (size in listOf(SnapshotSize.Desktop, SnapshotSize.Phone)) {
      for (theme in SnapshotTheme.entries) {
        paletteSnapshot("palette-downloads", size, theme, query = "image")
      }
    }
  }

  @Test
  fun searchToken_desktop_listsFailedTasks() {
    paletteSnapshot("palette-token", SnapshotSize.Desktop, SnapshotTheme.Light, query = "is:failed")
  }

  @Test
  fun discover_desktop_fallsThroughForPlainText() {
    for (theme in SnapshotTheme.entries) {
      paletteSnapshot("palette-discover", SnapshotSize.Desktop, theme, query = "blender for mac")
    }
  }

  @Test
  fun noMatches_withoutDiscover_saysSo() {
    for (theme in SnapshotTheme.entries) {
      paletteSnapshot("palette-none", SnapshotSize.Medium, theme, query = "zzqx", discover = false)
    }
  }

  @Test
  fun keyboard_desktop_movesTheHighlightToADownload() {
    for (theme in SnapshotTheme.entries) {
      paletteSnapshot("palette-keys", SnapshotSize.Desktop, theme, query = "image") {
        pressKey(Key.DirectionDown)
      }
    }
  }

  @Test
  fun settingsPage_desktop_jumpsWithASlash() {
    paletteSnapshot("palette-slash", SnapshotSize.SmallDesktop, SnapshotTheme.Light, query = "/s")
  }

  /**
   * Renders the app with the palette open over it at [size] in [theme], [query] typed and
   * [recent] rows run before, then runs [interact]. Without [discover] the navigation offers no
   * Discover, as on iOS and the web.
   */
  private fun paletteSnapshot(
    name: String,
    size: SnapshotSize,
    theme: SnapshotTheme,
    query: String = "",
    recent: List<String> = emptyList(),
    discover: Boolean = true,
    interact: suspend SnapshotScene.() -> Unit = {},
  ) {
    val environment = runBlocking(SnapshotHarness.ui) {
      SettingsEnvironment(theme, size.density.toMode())
    }
    // The field starts with the query selected, as it does with the list's search; End puts
    // the caret after it, as if it had been typed.
    val typed: suspend SnapshotScene.() -> Unit = {
      if (query.isNotEmpty()) pressKey(Key.MoveEnd)
      interact()
    }
    try {
      SnapshotHarness.capture("$name-${theme.id}-${size.id}", size, typed) {
        App(environment.controller)
        PaletteOverlay(environment, theme, size.density, query, PaletteHistory(recent), discover)
      }
    } finally {
      runBlocking(SnapshotHarness.ui) { environment.close() }
    }
  }

  private companion object {
    val SIZES = listOf(SnapshotSize.Desktop, SnapshotSize.Medium, SnapshotSize.Phone)

    const val LINK = "https://releases.ubuntu.com/24.04/ubuntu-24.04-live-server-amd64.iso"

    val RECENT = listOf(
      "command.retryFailed",
      "settings.Speed",
      "task.local/ubuntu",
      "device.nas.local:8642"
    )
  }
}

/** The palette as the shell opens it, with the shell's own commands, over [App]. */
@Composable
private fun PaletteOverlay(
  environment: SettingsEnvironment,
  theme: SnapshotTheme,
  density: KetchDensity,
  query: String,
  history: PaletteHistory,
  discover: Boolean,
) {
  val state = environment.controller.state
  val scope = rememberCoroutineScope()
  val clipboard = rememberSystemClipboard()
  val files = rememberFilePicker()
  val commands = remember(state) { ShellCommands(ShellState(state), scope, clipboard, files) }
  CompositionLocalProvider(
    LocalAppState provides state,
    LocalClock provides SampleData.CLOCK
  ) {
    KetchTheme(
      darkTheme = theme == SnapshotTheme.Dark,
      density = density.toMode(),
      reduceMotion = true,
    ) {
      CommandPalette(
        state = state,
        onDismiss = {},
        onCommand = commands::run,
        canRun = commands::binds,
        destinations = AppDestination.visible(discover && state.aiSettings.supported),
        initialQuery = query,
        history = history,
      )
    }
  }
}

private fun KetchDensity.toMode(): DensityMode = when (this) {
  KetchDensity.Compact -> DensityMode.Compact
  KetchDensity.Comfortable -> DensityMode.Comfortable
}
