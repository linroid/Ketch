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
import kotlin.test.BeforeTest
import kotlin.test.Test

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
    // A link the sample has no task for, so the sheet shows a fresh add.
    val link = "https://cdimage.debian.org/debian-cd/current/amd64/iso-cd/" +
      "debian-13.1.0-amd64-netinst.iso"
    appSnapshots("drop-link", listOf(SnapshotSize.Desktop, SnapshotSize.Phone)) {
      // What a link dragged from a browser reaches the app as: the address in its URI list.
      state.addDroppedFiles(listOf(droppedLinkList(link)))
    }
  }

  /** The overlay over the real app, as it shows while a drag hovers the window. */
  private fun overlaySnapshot(size: SnapshotSize, theme: SnapshotTheme) {
    val density = size.density.toMode()
    withSample(theme, density) { env ->
      SnapshotHarness.capture("drop-overlay-${theme.id}-${size.id}", size) {
        Box(Modifier.fillMaxSize()) {
          App(env.controller)
          KetchTheme(darkTheme = theme == SnapshotTheme.Dark, density = density) {
            DropOverlay(compact = false)
          }
        }
      }
    }
  }
}
