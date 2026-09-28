package com.linroid.ketch.app.icons

import androidx.compose.ui.graphics.vector.PathNode
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class KetchIconTest {
  @Test
  fun circularIconsRetainBothHalvesWhenParsedByCompose() {
    val circularIcons = listOf(
      KetchIcon.Search, KetchIcon.Settings, KetchIcon.Appearance,
      KetchIcon.Queued, KetchIcon.Done, KetchIcon.Failed, KetchIcon.Remote, KetchIcon.Info,
    )
    for (icon in circularIcons) {
      val nodes = PathParser().parsePathString(icon.data.strokes.first()).toNodes()
      val arcs = nodes.count { it is PathNode.ArcTo || it is PathNode.RelativeArcTo }
      assertEquals(2, arcs, "${icon.name} must retain both semicircles")
    }
  }

  @Test
  fun fileTypeCirclesRetainBothHalvesWhenParsedByCompose() {
    val circles = mapOf(
      "FileAudio notes" to KetchIcon.FileAudio.data.fills,
      "FileImage sun" to KetchIcon.FileImage.data.fills,
      "FileDesign hole" to KetchIcon.FileDesign.data.fills,
      "FileDiskImage disc" to KetchIcon.FileDiskImage.data.strokes.take(1),
      "FileDiskImage hub" to KetchIcon.FileDiskImage.data.fills,
      "FileDatabase lid" to KetchIcon.FileDatabase.data.strokes.take(1),
      "FileWeb globe" to KetchIcon.FileWeb.data.strokes.take(1),
      "FileKey bow" to KetchIcon.FileKey.data.strokes.take(1),
      "FileFont bowl" to KetchIcon.FileFont.data.strokes.filter { " 0 1 0 " in it },
    )
    for ((name, paths) in circles) {
      assertTrue(paths.isNotEmpty(), "$name has no path")
      for (d in paths) {
        val nodes = PathParser().parsePathString(d).toNodes()
        val arcs = nodes.count { it is PathNode.ArcTo || it is PathNode.RelativeArcTo }
        assertEquals(2, arcs, "$name must retain both halves")
      }
    }
  }
}
