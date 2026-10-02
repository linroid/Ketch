package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.App
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.LocalWindowChrome
import com.linroid.ketch.app.theme.WindowChrome
import com.linroid.ketch.app.ui.shell.PhoneBottomBar
import com.linroid.ketch.app.ui.shell.ShortcutSheet
import com.linroid.ketch.app.ui.shell.shortcutGroups
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.RemoteConfig
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The shell (W3-SHELL): the canvas wash with the sidebar and the content card on wide windows,
 * the rail on medium ones and the phone's top bar, Add button and bottom bar, at the widths the
 * spec screenshots (360, 600, 840, 1024, 1280 and 1440 dp); see [SnapshotHarness] for how to
 * run it.
 */
class ShellSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun shell_specWidths_layOutTheShell() {
    appSnapshots("shell", ShellWidths)
  }

  @Test
  fun shell_noDownloads_keepsTheChrome() {
    appSnapshots("shell-empty", listOf(SnapshotSize.Desktop, Phone360), data = SampleData::empty)
  }

  @Test
  fun shell_collapsedSidebar_showsTheRail() {
    appSnapshots(
      name = "shell-collapsed",
      sizes = listOf(SnapshotSize.Desktop),
      data = {
        SampleData(
          tasks = SampleData.downloads().tasks,
          ui = { it.copy(sidebarCollapsed = true) },
        )
      },
    )
  }

  @Test
  fun shell_macChrome_leavesRoomForTheTrafficLights() {
    for (size in listOf(SnapshotSize.Desktop, Width840, SnapshotSize.Medium)) {
      for (theme in SnapshotTheme.entries) {
        macSnapshot("shell-mac", size, theme)
      }
    }
  }

  @Test
  fun shell_devicesDestination_showsTheDevicesPage() {
    appSnapshots("shell-devices", listOf(SnapshotSize.Desktop)) {
      // The sidebar's second destination, under the 60 dp title zone.
      scene.click(100.dp, 114.dp)
    }
  }

  @Test
  fun shell_keyboardFocus_ringsTheSidebar() {
    val light = listOf(SnapshotTheme.Light)
    appSnapshots("shell-focus", listOf(SnapshotSize.Desktop), themes = light) {
      repeat(2) { scene.pressKey(Key.Tab) }
    }
  }

  @Test
  fun shell_phoneSearch_offersTheLink() {
    appSnapshots("shell-phone-search", listOf(SnapshotSize.Phone)) {
      state.requestSearchFocus()
      scene.settle()
      search("https://releases.ubuntu.com/24.04/ubuntu-24.04-live-server-amd64.iso")
    }
  }

  @Test
  fun shell_phoneMenu_listsTheQueueCommands() {
    appSnapshots("shell-phone-menu", listOf(SnapshotSize.Phone)) {
      // The ⋮ button at the end of the top bar.
      scene.click(362.dp, 32.dp)
    }
  }

  @Test
  fun shell_phoneDevices_opensFromTheMenu() {
    appSnapshots("shell-phone-devices", listOf(SnapshotSize.Phone)) {
      // The ⋮ button, then its Devices item.
      scene.click(362.dp, 32.dp)
      scene.settle()
      scene.click(100.dp, 756.dp)
    }
  }

  @Test
  fun shell_pointerOnAPhoneWidth_keepsThePennantWhole() {
    appSnapshots("shell-phone-pointer", listOf(PhonePointer), themes = listOf(SnapshotTheme.Light))
  }

  @Test
  fun shell_manyDevicesInAShortWindow_scrollsTheDevices() {
    appSnapshots(
      name = "shell-many-devices",
      sizes = listOf(Short1024, Short760),
      data = { SampleData(tasks = SampleData.downloads().tasks, remotes = ManyRemotes) },
    )
  }

  @Test
  fun shell_settings_takesTheCardsPlace() {
    val sizes = listOf(SnapshotSize.Desktop, SnapshotSize.Medium, SnapshotSize.Phone)
    appSnapshots("shell-settings", sizes) { openSettings() }
  }

  @Test
  fun phoneBottomBar_threeDestinations_showsEachOne() {
    for (theme in SnapshotTheme.entries) {
      snapshot("shell-bottom-bar", BottomBarSize, theme) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
          PhoneBottomBar(
            destinations = AppDestination.entries,
            selected = AppDestination.Downloads,
            onSelect = {},
          )
        }
      }
    }
  }

  @Test
  fun shortcutSheet_desktopAndPhone_listsEveryChord() {
    val groups = shortcutGroups(KeyboardPlatform.Mac) { true }
    for (size in listOf(SnapshotSize.Desktop, SnapshotSize.Phone)) {
      for (theme in SnapshotTheme.entries) {
        snapshot("shortcut-sheet", size, theme) { ShortcutSheet(groups, onDismissRequest = {}) }
      }
    }
  }
}

private val Phone360 = SnapshotSize(360.dp, 760.dp, KetchDensity.Comfortable)
private val Width600 = SnapshotSize(600.dp, 760.dp, KetchDensity.Compact)
private val Width840 = SnapshotSize(840.dp, 720.dp, KetchDensity.Compact)
private val Width1440 = SnapshotSize(1440.dp, 900.dp, KetchDensity.Compact)
private val BottomBarSize = SnapshotSize(390.dp, 160.dp, KetchDensity.Comfortable)
private val PhonePointer = SnapshotSize(390.dp, 844.dp, KetchDensity.Compact)
private val Short1024 = SnapshotSize(1024.dp, 480.dp, KetchDensity.Compact)
private val Short760 = SnapshotSize(760.dp, 480.dp, KetchDensity.Compact)

// More devices than the sidebar shows at once, one of them with a long name.
private val ManyRemotes = listOf(
  SampleData.NAS,
  RemoteConfig(host = "den-pc.local", name = "Den-PC", watch = false),
  RemoteConfig(host = "media.local", name = "Living room media server upstairs", watch = false),
  RemoteConfig(host = "seedbox.example.net", port = 443, secure = true, watch = false),
  RemoteConfig(host = "office.local", name = "Office", watch = false),
  RemoteConfig(host = "pi.local", name = "Raspberry Pi", watch = false),
  RemoteConfig(host = "laptop.local", name = "Travel laptop", watch = false),
)

private val ShellWidths = listOf(
  Width1440,
  SnapshotSize.Desktop,
  SnapshotSize.SmallDesktop,
  Width840,
  Width600,
  SnapshotSize.Phone,
  Phone360,
)

/** Renders the app under a transparent macOS title bar, with the traffic lights drawn in. */
private fun macSnapshot(name: String, size: SnapshotSize, theme: SnapshotTheme): File {
  val density = when (size.density) {
    KetchDensity.Compact -> DensityMode.Compact
    KetchDensity.Comfortable -> DensityMode.Comfortable
  }
  val data = SampleData.downloads()
  val environment = runBlocking(SnapshotHarness.ui) { SampleEnvironment(data, theme, density) }
  try {
    runBlocking(SnapshotHarness.ui) {
      withTimeoutOrNull(5.seconds) { environment.start() }
        ?: error("The task list of $name never listed every sample task")
    }
    return SnapshotHarness.capture("$name-${theme.id}-${size.id}", size) {
      MacWindow(environment.controller)
    }
  } finally {
    runBlocking(SnapshotHarness.ui) { environment.close() }
  }
}

@Composable
private fun MacWindow(controller: AppController) {
  CompositionLocalProvider(LocalWindowChrome provides WindowChrome(top = 28.dp, leading = 78.dp)) {
    Box(Modifier.fillMaxSize()) {
      App(controller)
      Canvas(Modifier.size(78.dp, 28.dp)) {
        val radius = 6.dp.toPx()
        TrafficLights.forEachIndexed { index, color ->
          val center = Offset((14 + index * 20).dp.toPx(), 14.dp.toPx())
          drawCircle(color, radius, center)
          drawCircle(Color.Black.copy(alpha = 0.12f), radius, center, style = Stroke(1f))
        }
      }
    }
  }
}

private val TrafficLights = listOf(Color(0xFFFF5F57), Color(0xFFFEBC2E), Color(0xFF28C840))
