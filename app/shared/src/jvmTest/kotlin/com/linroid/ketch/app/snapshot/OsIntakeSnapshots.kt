package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.App
import com.linroid.ketch.app.platform.droppedLinkList
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.DropOverlay
import com.linroid.ketch.config.DensityMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * What links and files from other apps look like on the way in: the drop overlay over the app,
 * its compact form in a small area, and the add sheet a dropped link opens; see
 * [SnapshotHarness] for how to run it.
 */
class OsIntakeSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun dropOverlay_overTheApp_showsOneBerth() {
    val sizes = listOf(SnapshotSize.Desktop, SnapshotSize.Medium, SnapshotSize.Phone)
    for (size in sizes) {
      for (theme in SnapshotTheme.entries) overlaySnapshot(size, theme)
    }
  }

  @Test
  fun dropOverlay_compact_fitsASmallArea() {
    val size = SnapshotSize(480.dp, 220.dp, KetchDensity.Compact)
    for (theme in SnapshotTheme.entries) {
      snapshot("drop-overlay-compact", size, theme) {
        Box(Modifier.padding(KetchTheme.spacing.s4).size(448.dp, 188.dp)) {
          DropOverlay(compact = true)
        }
      }
    }
  }

  @Test
  fun droppedLink_onTheWindow_opensTheAddSheet() {
    val link = "https://releases.ubuntu.com/24.04/ubuntu-24.04-desktop-amd64.iso"
    appSnapshots("drop-link", listOf(SnapshotSize.Desktop, SnapshotSize.Phone)) {
      // What a link dragged from a browser reaches the app as.
      state.addDroppedFiles(listOf(droppedLinkList("Ubuntu 24.04\n$link")))
    }
  }

  /** The overlay over the real app, as it shows while a drag hovers the window. */
  private fun overlaySnapshot(size: SnapshotSize, theme: SnapshotTheme) {
    val density = when (size.density) {
      KetchDensity.Compact -> DensityMode.Compact
      KetchDensity.Comfortable -> DensityMode.Comfortable
    }
    val environment = runBlocking(SnapshotHarness.ui) {
      SampleEnvironment(SampleData.downloads(), theme, density)
    }
    try {
      runBlocking(SnapshotHarness.ui) {
        withTimeoutOrNull(5.seconds) { environment.start() } ?: error("The sample never loaded")
      }
      SnapshotHarness.capture("drop-overlay-${theme.id}-${size.id}", size) {
        Box(Modifier.fillMaxSize()) {
          App(environment.controller)
          KetchTheme(darkTheme = theme == SnapshotTheme.Dark, density = density) {
            DropOverlay(compact = false)
          }
        }
      }
    } finally {
      runBlocking(SnapshotHarness.ui) { environment.close() }
    }
  }
}
