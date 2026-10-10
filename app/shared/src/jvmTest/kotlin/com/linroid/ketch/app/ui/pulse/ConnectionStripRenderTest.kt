package com.linroid.ketch.app.ui.pulse

import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.CellPaint
import com.linroid.ketch.app.components.KetchCellGrid
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.snapshot.ConnectionSamples
import com.linroid.ketch.app.snapshot.SampleData
import com.linroid.ketch.app.snapshot.SnapshotTheme
import com.linroid.ketch.app.snapshot.frames
import com.linroid.ketch.app.snapshot.nodes
import com.linroid.ketch.app.snapshot.sendKey
import com.linroid.ketch.app.snapshot.withSample
import com.linroid.ketch.app.snapshot.withScene
import com.linroid.ketch.app.state.ConnectionGridState
import com.linroid.ketch.app.state.PulseState
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.DensityMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** The Pulse bar's connections grid, rendered: when it shows, and how its cells take input. */
class ConnectionStripRenderTest {
  @Test
  fun pulseBar_offDownloadsThenOnIt_showsTheStripOnlyOnDownloads() {
    val sample = SampleData.downloads()
    val data = SampleData(sample.tasks, connections = ConnectionSamples.mixed())
    withSample(SnapshotTheme.Light, data = data) { environment ->
      val state = environment.controller.state
      val onDownloads = mutableStateOf(false)
      withScene(
        width = 1280,
        height = 40,
        content = {
          theme { PulseBar(state, showConnections = onDownloads.value) }
        },
      ) {
        frames(FRAMES)
        assertEquals(emptyList(), connectionNodes(), "No strip off the Downloads page")

        onDownloads.value = true
        frames(FRAMES)

        val grid = ConnectionSamples.grid(ConnectionSamples.mixed(), limit = STRIP_LIMIT)
        val expected = "${grid.total} connections, ${grid.downloading} downloading, " +
          "${grid.uploading} uploading"
        assertEquals(listOf(expected), connectionNodes())
      }
    }
  }

  @Test
  fun pulseBarContent_noConnectionsOrNoSupport_drawsNoStrip() {
    val grids = listOf(
      ConnectionGridState(supported = true),
      ConnectionGridState(supported = false, unsupported = listOf(verbatim("NAS-Basement"))),
    )
    for (grid in grids) {
      withScene(
        width = 1280,
        height = 40,
        content = {
          theme {
            PulseBarContent(
              pulse = PulseState(),
              unread = 0,
              selection = null,
              onShowTab = {},
              onSpeedClick = {},
              onHealthClick = {},
              onActivityClick = {},
              pill = {},
              connections = { ConnectionStrip(grid, onClick = {}) },
            )
          }
        },
      ) {
        frames(FRAMES)
        assertEquals(emptyList(), connectionNodes())
      }
    }
  }

  @Test
  fun cellGrid_pointerAndKeys_reportHoveredAndSelectedCells() {
    val hovered = mutableListOf<Int?>()
    val selected = mutableListOf<Int>()
    val focus = FocusRequester()
    withScene(
      width = 200,
      height = 60,
      content = {
        theme {
          KetchCellGrid(
            cells = List(10) { CellPaint(Color.Gray) },
            cellSize = 10.dp,
            gap = 2.dp,
            onHover = { hovered += it },
            onSelect = { selected += it },
            description = "grid",
            modifier = Modifier.focusRequester(focus),
          )
        }
      },
    ) {
      frames(FRAMES)
      // Row-major in 200 px: cell 2 starts at 24 px.
      move(Offset(25f, 5f))
      frames(2)
      assertEquals(2, hovered.last())
      click(Offset(25f, 5f))
      frames(2)
      assertEquals(listOf(2), selected)
      sendPointerEvent(PointerEventType.Exit, Offset(-5f, -5f))
      frames(2)
      assertEquals(null, hovered.last())

      focus.requestFocus()
      frames(2)
      sendKey(Key.DirectionRight)
      sendKey(Key.DirectionRight)
      frames(2)
      assertEquals(2, hovered.last(), "The keyboard's cell starts at the first and moves right")
      sendKey(Key.Enter)
      frames(2)
      assertEquals(listOf(2, 2), selected)
    }
  }

  @Composable
  private fun theme(content: @Composable () -> Unit) {
    KetchTheme(darkTheme = false, density = DensityMode.Compact, reduceMotion = true) {
      content()
    }
  }

  private fun ImageComposeScene.connectionNodes(): List<String> = nodes()
    .mapNotNull { it.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull() }
    .filter { "connection" in it }

  private fun ImageComposeScene.move(at: Offset) {
    sendPointerEvent(PointerEventType.Move, at)
  }

  private fun ImageComposeScene.click(at: Offset) {
    sendPointerEvent(PointerEventType.Move, at)
    sendPointerEvent(
      PointerEventType.Press,
      at,
      buttons = PointerButtons(isPrimaryPressed = true),
      button = PointerButton.Primary,
    )
    sendPointerEvent(
      PointerEventType.Release,
      at,
      buttons = PointerButtons(),
      button = PointerButton.Primary,
    )
  }

  private companion object {
    const val FRAMES = 30
  }
}
