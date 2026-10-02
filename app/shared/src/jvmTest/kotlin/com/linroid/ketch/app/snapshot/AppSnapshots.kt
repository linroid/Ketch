package com.linroid.ketch.app.snapshot

import androidx.compose.ui.input.key.Key
import com.linroid.ketch.app.state.StatusFilter
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The app root at every size in light and dark, over downloads in every state and over none;
 * see [SnapshotHarness] for how to run it.
 */
class AppSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun downloads_everySizeAndTheme_rendersEveryState() {
    appSnapshots("downloads")
  }

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
    appSnapshots("settings", sizes) { openSettings() }
    appSnapshots("devices", sizes) { openDevices() }
    appSnapshots("failed-tab", sizes) { showTab(StatusFilter.Failed) }
    appSnapshots("keyboard-focus", listOf(SnapshotSize.Desktop)) {
      repeat(3) { scene.pressKey(Key.Tab) }
    }
  }
}
