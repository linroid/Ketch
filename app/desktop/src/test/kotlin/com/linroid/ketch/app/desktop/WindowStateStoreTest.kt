package com.linroid.ketch.app.desktop

import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import java.awt.Rectangle
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

class WindowStateStoreTest {
  private val dir = Files.createTempDirectory("window-state").toFile()
  private val file = File(dir, "nested/window.properties")
  private val store = WindowStateStore(file)

  private val primary = Rectangle(0, 25, 1440, 875)
  private val secondary = Rectangle(-1920, 0, 1920, 1080)

  @AfterTest
  fun cleanUp() {
    dir.deleteRecursively()
  }

  @Test
  fun load_missingFile_returnsNull() {
    assertNull(store.load())
  }

  @Test
  fun save_thenLoad_restoresBounds() {
    val bounds = WindowBounds(x = -1800, y = 40, width = 1200, height = 800, maximized = true)
    store.save(bounds)
    assertEquals(bounds, store.load())
  }

  @Test
  fun load_malformedNumber_returnsNull() {
    file.parentFile.mkdirs()
    file.writeText("x=10\ny=20\nwidth=wide\nheight=700\nmaximized=false\n")
    assertNull(store.load())
  }

  @Test
  fun load_missingKey_returnsNull() {
    file.parentFile.mkdirs()
    file.writeText("x=10\ny=20\nwidth=1000\nmaximized=false\n")
    assertNull(store.load())
  }

  @Test
  fun load_nonPositiveSize_returnsNull() {
    file.parentFile.mkdirs()
    file.writeText("x=10\ny=20\nwidth=0\nheight=700\nmaximized=false\n")
    assertNull(store.load())
  }

  @Test
  fun initialWindowState_nothingSaved_opensDefaultSizeCentered() {
    val state = initialWindowState(saved = null, screens = listOf(primary))
    assertCentered(state)
    assertEquals(DEFAULT_WINDOW_WIDTH.dp, state.size.width)
    assertEquals(DEFAULT_WINDOW_HEIGHT.dp, state.size.height)
    assertEquals(WindowPlacement.Floating, state.placement)
  }

  @Test
  fun initialWindowState_nothingSavedOnSmallScreen_shrinksToFit() {
    val small = Rectangle(0, 0, 800, 600)
    val state = initialWindowState(saved = null, screens = listOf(small))
    assertEquals(800.dp, state.size.width)
    assertEquals(600.dp, state.size.height)
  }

  @Test
  fun initialWindowState_savedOnScreen_restoresBounds() {
    val saved = WindowBounds(x = 100, y = 120, width = 900, height = 650, maximized = false)
    val state = initialWindowState(saved, screens = listOf(primary))
    assertPosition(state, x = 100, y = 120)
    assertEquals(900.dp, state.size.width)
    assertEquals(650.dp, state.size.height)
  }

  @Test
  fun initialWindowState_savedOnSecondaryScreen_restoresThere() {
    val saved = WindowBounds(x = -1700, y = 50, width = 1600, height = 1000, maximized = false)
    val state = initialWindowState(saved, screens = listOf(primary, secondary))
    assertPosition(state, x = -1700, y = 50)
    assertEquals(1600.dp, state.size.width)
  }

  @Test
  fun initialWindowState_screenDisconnected_centersSavedSizeOnPrimary() {
    val saved = WindowBounds(x = -1700, y = 50, width = 1000, height = 700, maximized = false)
    val state = initialWindowState(saved, screens = listOf(primary))
    assertCentered(state)
    assertEquals(1000.dp, state.size.width)
    assertEquals(700.dp, state.size.height)
  }

  @Test
  fun initialWindowState_partlyOffScreen_movesInside() {
    val saved = WindowBounds(x = 1000, y = 0, width = 800, height = 600, maximized = false)
    val state = initialWindowState(saved, screens = listOf(primary))
    assertPosition(state, x = 1440 - 800, y = 25)
  }

  @Test
  fun initialWindowState_largerThanScreen_shrinksToScreen() {
    val saved = WindowBounds(x = 0, y = 25, width = 2560, height = 1440, maximized = false)
    val state = initialWindowState(saved, screens = listOf(primary))
    assertPosition(state, x = 0, y = 25)
    assertEquals(1440.dp, state.size.width)
    assertEquals(875.dp, state.size.height)
  }

  @Test
  fun initialWindowState_savedMaximized_restoresMaximized() {
    val saved = WindowBounds(x = 100, y = 120, width = 900, height = 650, maximized = true)
    val state = initialWindowState(saved, screens = listOf(primary))
    assertEquals(WindowPlacement.Maximized, state.placement)
    assertPosition(state, x = 100, y = 120)
  }

  private fun assertCentered(state: WindowState) {
    val position = assertIs<WindowPosition.Aligned>(state.position)
    assertEquals(Alignment.Center, position.alignment)
  }

  private fun assertPosition(state: WindowState, x: Int, y: Int) {
    val position = assertIs<WindowPosition.Absolute>(state.position)
    assertEquals(x.dp, position.x)
    assertEquals(y.dp, position.y)
  }
}
