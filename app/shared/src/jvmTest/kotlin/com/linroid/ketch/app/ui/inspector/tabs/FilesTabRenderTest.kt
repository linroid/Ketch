package com.linroid.ketch.app.ui.inspector.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.app.snapshot.SnapshotHarness
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.DensityMode
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertTrue

class FilesTabRenderTest {
  @Test
  fun filesTab_threeHundredFileTorrent_staysWithin360DpWithoutConnectionRows() {
    val files = (0 until FILES).map { SourceFile(it.toString(), "Pack/episode-$it.mkv", FILE_SIZE) }
    val segments = files.mapIndexed { index, _ ->
      val start = index * FILE_SIZE
      Segment(index, start, start + FILE_SIZE - 1, if (index % 3 == 0) FILE_SIZE else 0)
    }
    val url = "magnet:?xt=urn:btih:4c7f3e2b9d1a8f6e5c0b7a3d2e1f9c8b7a6d5e4f"
    val source = ResolvedSource(url, "torrent", FILES * FILE_SIZE, true, null, 1, files = files)
    val request = DownloadRequest(url = url, resolvedSource = source)
    val state = DownloadState.Downloading(DownloadProgress(0, FILES * FILE_SIZE, 1000))
    val row = ListFixtures.row("t", state, request = request).copy(segments = segments)
    var height = -1

    val texts = runBlocking(SnapshotHarness.ui) {
      val scene = ImageComposeScene(
        width = WIDTH,
        height = WINDOW_HEIGHT,
        density = Density(1f),
        coroutineContext = SnapshotHarness.ui,
      ) {
        KetchTheme(darkTheme = false, density = DensityMode.Compact, reduceMotion = true) {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            Box(Modifier.onSizeChanged { height = it.height }) { FilesTab(row) }
          }
        }
      }
      try {
        repeat(FRAMES) { frame ->
          scene.render(frame * FRAME_NANOS)
          delay(FRAME_MILLIS)
        }
        scene.semanticsOwners.flatMap { it.unmergedRootSemanticsNode.texts() }
      } finally {
        scene.close()
      }
    }

    assertTrue(height in 1..MAX_HEIGHT, "The Files tab is $height dp tall")
    assertTrue(texts.any { "of 300 files done" in it }, "No file summary in $texts")
    assertTrue(texts.none { it.contains("connection", ignoreCase = true) }, "$texts")
  }

  private fun SemanticsNode.texts(): List<String> {
    val own = config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
      config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    return own + children.flatMap { it.texts() }
  }

  private companion object {
    const val FILES = 300
    const val FILE_SIZE = 1_000_000L
    const val WIDTH = 320
    const val WINDOW_HEIGHT = 1200
    const val MAX_HEIGHT = 360
    const val FRAMES = 10
    const val FRAME_MILLIS = 16L
    const val FRAME_NANOS = 16_000_000L
  }
}
