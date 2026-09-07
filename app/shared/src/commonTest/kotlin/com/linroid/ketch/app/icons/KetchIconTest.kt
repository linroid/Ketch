package com.linroid.ketch.app.icons

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.test.Test
import kotlin.test.assertEquals

class KetchIconTest {
  @Test
  fun circularIconsRetainBothHalvesWhenParsedByCompose() {
    val circularIcons = listOf(
      KetchIcon.Search, KetchIcon.Settings,
      KetchIcon.Queued, KetchIcon.Done, KetchIcon.Failed, KetchIcon.Remote,
    )
    for (icon in circularIcons) {
      val nodes = PathParser().parsePathString(icon.data.strokes.first()).toNodes()
      val arcs = nodes.count { it is PathNode.ArcTo || it is PathNode.RelativeArcTo }
      assertEquals(2, arcs, "${icon.name} must retain both semicircles")
    }
  }
}
