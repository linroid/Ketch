package com.linroid.ketch.app.ui.downloads

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.snapshot.SampleData
import com.linroid.ketch.app.snapshot.SnapshotClock
import com.linroid.ketch.app.snapshot.SnapshotTheme
import com.linroid.ketch.app.snapshot.frames
import com.linroid.ketch.app.snapshot.nodes
import com.linroid.ketch.app.snapshot.sendKey
import com.linroid.ketch.app.snapshot.withSample
import com.linroid.ketch.app.snapshot.withScene
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.DensityMode
import com.linroid.ketch.config.DownloadsLayout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class DownloadsContextMenuTest {
  @Test
  fun emptySpace_tableAndList_showTheSameActionsAsHeaderMenu() {
    for (layout in listOf(DownloadsLayout.Table, DownloadsLayout.List)) {
      downloadsScene(layout) { state, data ->
        state.selectedKeys = setOf(data.keyOf(UBUNTU))
        frames(FRAMES)
        val selected = state.selectedKeys
        val baseline = texts()
        val more = nodes().single {
          it.config.getOrNull(SemanticsProperties.ContentDescription) == listOf("More")
        }
        checkNotNull(more.config.getOrNull(SemanticsActions.OnClick)?.action).invoke()
        frames(FRAMES)
        val headerMenu = texts() - baseline
        assertTrue("Pause all" in headerMenu)
        assertTrue("Copy all links" in headerMenu)
        sendKey(Key.Escape)
        frames(FRAMES)

        rightClick(Offset(40f, HEIGHT - 40f))

        assertEquals(headerMenu, texts() - baseline)
        assertEquals(selected, state.selectedKeys, "An empty-space click keeps the selection")
      }
    }
  }

  @Test
  fun task_tableAndList_openOnlyTaskMenu_andKeepSelectedBatch() {
    for (layout in listOf(DownloadsLayout.Table, DownloadsLayout.List)) {
      downloadsScene(layout) { state, _ ->
        val selected = state.taskList.rows.value.mapTo(LinkedHashSet()) { it.key }
        state.selectedKeys = selected
        frames(FRAMES)
        val row = nodes().first {
          it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == UBUNTU } == true
        }

        rightClick(row.boundsInRoot.center)

        assertTrue("Pause 2 downloads" in texts(), "The selected tasks have batch actions")
        assertFalse("Pause all" in texts(), "The parent must not also open the global menu")
        assertEquals(selected, state.selectedKeys)
      }
    }
  }

  @Test
  fun emptyDevice_rightClick_opensGlobalMenu() {
    downloadsScene(DownloadsLayout.List, empty = true) { _, _ ->
      rightClick(Offset(40f, HEIGHT - 40f))
      assertTrue("Copy all links" in texts())
      assertTrue("Pause all" in texts())
    }
  }

  @Test
  fun tableHeader_rightClick_opensColumnChooser_insteadOfGlobalMenu() {
    downloadsScene(DownloadsLayout.Table) { _, _ ->
      val header = nodes().first {
        it.config.getOrNull(SemanticsProperties.Text)?.any { text -> text.text == "NAME" } == true
      }
      rightClick(header.boundsInRoot.center)
      assertTrue("Reset columns" in texts())
      assertFalse("Pause all" in texts())
    }
  }

  private fun downloadsScene(
    layout: DownloadsLayout,
    empty: Boolean = false,
    test: suspend ImageComposeScene.(AppState, SampleData) -> Unit,
  ) {
    val data = SampleData(
      tasks = if (empty) emptyList() else SampleData.downloads().tasks.take(2),
      remotes = emptyList(),
      ui = { it.copy(layout = layout) },
    )
    withSample(SnapshotTheme.Light, data = data) { environment ->
      val state = environment.controller.state
      withScene(WIDTH, HEIGHT, content = {
        CompositionLocalProvider(LocalClock provides SampleData.CLOCK) {
          KetchTheme(darkTheme = false, density = DensityMode.Compact, reduceMotion = true) {
            DownloadsScreen(state, KetchLayoutInfo.of(WIDTH.dp), Modifier.fillMaxSize())
          }
        }
      }) {
        frames(FRAMES)
        test(state, data)
      }
    }
  }

  private suspend fun ImageComposeScene.rightClick(position: Offset) {
    sendPointerEvent(PointerEventType.Move, position, timeMillis = SnapshotClock.millis)
    sendPointerEvent(
      eventType = PointerEventType.Press,
      position = position,
      timeMillis = SnapshotClock.millis,
      buttons = PointerButtons(isSecondaryPressed = true),
      button = PointerButton.Secondary,
    )
    sendPointerEvent(
      eventType = PointerEventType.Release,
      position = position,
      timeMillis = SnapshotClock.millis,
      buttons = PointerButtons(),
      button = PointerButton.Secondary,
    )
    frames(FRAMES)
  }

  private fun ImageComposeScene.texts(): Set<String> = nodes()
    .flatMap { it.config.getOrNull(SemanticsProperties.Text).orEmpty() }
    .mapTo(LinkedHashSet()) { it.text }

  private companion object {
    const val WIDTH = 1100
    const val HEIGHT = 800
    const val FRAMES = 12
    const val UBUNTU = "ubuntu-24.04-desktop-amd64.iso"
  }
}
