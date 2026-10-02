package com.linroid.ketch.app.snapshot

import androidx.compose.ui.input.key.Key
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The app root over no downloads at every size in light and dark, and its add sheet, device
 * switcher and keyboard focus over downloads in every state; see [SnapshotHarness] for how to
 * run it.
 * [TableSnapshots] and [ShellSnapshots] show the downloads themselves at every size.
 */
class AppSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun empty_everySizeAndTheme_rendersEmptyState() {
    appSnapshots("empty", data = SampleData::empty)
  }

  @Test
  fun surfaces_desktopAndPhone_openThroughIntents() {
    val sizes = listOf(SnapshotSize.Desktop, SnapshotSize.Phone)
    appSnapshots("add-sheet", sizes) {
      openAddSheet("https://download.blender.org/release/Blender4.2/blender-4.2-linux-x64.tar.xz")
    }
    appSnapshots("devices", sizes) { openDevices() }
    appSnapshots("keyboard-focus", listOf(SnapshotSize.Desktop)) {
      repeat(3) { scene.pressKey(Key.Tab) }
    }
  }
}
