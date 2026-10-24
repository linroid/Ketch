package com.linroid.ketch.app.snapshot

import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * The desktop app at its normal size and zoomed in from Settings → General → Zoom, which draws
 * its text and controls larger in the same window; see [SnapshotHarness] for how to run it.
 */
class ZoomSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun app_zoom_drawsTextAndControlsLarger() {
    for (zoom in listOf(100, 150)) {
      for (theme in SnapshotTheme.entries) {
        appSnapshot("zoom-$zoom", SnapshotSize.Desktop, theme) { state.appSettings.saveZoom(zoom) }
      }
    }
  }
}
