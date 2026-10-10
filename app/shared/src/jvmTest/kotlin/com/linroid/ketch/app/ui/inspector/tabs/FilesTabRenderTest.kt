package com.linroid.ketch.app.ui.inspector.tabs

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.app.snapshot.frames
import com.linroid.ketch.app.snapshot.nodes
import com.linroid.ketch.app.snapshot.withScene
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.DensityMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class FilesTabRenderTest {
  @Test
  fun filesTab_threeHundredFileTorrent_staysWithin360DpWithoutConnectionRows() {
    val (height, texts) = render(torrent(files = 300))

    assertTrue(height in 1..DEFAULT_MAX_HEIGHT, "The Files tab is $height dp tall")
    assertTrue(texts.any { "of 300 files done" in it }, "No file summary in $texts")
    assertTrue(texts.none { it.contains("connection", ignoreCase = true) }, "$texts")
  }

  @Test
  fun filesTab_longListWithMaxHeight_fillsIt() {
    val (height) = render(torrent(files = 300), maxHeight = FILL_HEIGHT.dp)

    assertEquals(FILL_HEIGHT, height)
  }

  @Test
  fun filesTab_shortListWithMaxHeight_wrapsItsRows() {
    val (height) = render(torrent(files = 3), maxHeight = FILL_HEIGHT.dp)

    assertTrue(height in 1 until FILL_HEIGHT, "The Files tab is $height dp tall")
  }

  /** A downloading torrent of [files] equal files, every third one done. */
  private fun torrent(files: Int): TaskRow {
    val sources = (0 until files).map {
      SourceFile(it.toString(), "Pack/episode-$it.mkv", FILE_SIZE)
    }
    val segments = sources.mapIndexed { index, _ ->
      val start = index * FILE_SIZE
      Segment(index, start, start + FILE_SIZE - 1, if (index % 3 == 0) FILE_SIZE else 0)
    }
    val url = "magnet:?xt=urn:btih:4c7f3e2b9d1a8f6e5c0b7a3d2e1f9c8b7a6d5e4f"
    val total = files * FILE_SIZE
    val source = ResolvedSource(url, "torrent", total, true, null, 1, files = sources)
    val request = DownloadRequest(url = url, resolvedSource = source)
    val state = DownloadState.Downloading(DownloadProgress(0, total, 1000))
    return ListFixtures.row("t", state, request = request).copy(segments = segments)
  }

  /** The Files tab of [row] in a scrolling column, as the inspector shows it, at 1 px per dp. */
  private fun render(row: TaskRow, maxHeight: Dp = Dp.Unspecified): Rendered {
    var height = -1
    val texts = withScene(
      width = WIDTH,
      height = WINDOW_HEIGHT,
      content = {
        KetchTheme(darkTheme = false, density = DensityMode.Compact, reduceMotion = true) {
          Column(Modifier.verticalScroll(rememberScrollState())) {
            Box(Modifier.onSizeChanged { height = it.height }) {
              FilesTabContent(row, maxHeight = maxHeight)
            }
          }
        }
      },
    ) {
      frames(FRAMES)
      nodes().flatMap { it.texts() }
    }
    return Rendered(height, texts)
  }

  /** The tab's height in dp and the texts it shows. */
  private data class Rendered(val height: Int, val texts: List<String>)

  private fun SemanticsNode.texts(): List<String> =
    config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
      config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

  private companion object {
    const val FILE_SIZE = 1_000_000L
    const val WIDTH = 320
    const val WINDOW_HEIGHT = 1200
    const val DEFAULT_MAX_HEIGHT = 360
    const val FILL_HEIGHT = 700
    const val FRAMES = 10
  }
}
