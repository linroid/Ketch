package com.linroid.ketch.app.desktop

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TrayMenuPlacementTest {
  // A 1920 × 1080 screen with a 48-point taskbar along its bottom edge.
  private val primary = TrayScreen(
    bounds = IntRect(0, 0, 1920, 1080),
    workArea = IntRect(0, 0, 1920, 1032),
  )

  // A larger screen to the right of it, its taskbar also at the bottom.
  private val right = TrayScreen(
    bounds = IntRect(1920, 0, 4480, 1440),
    workArea = IntRect(1920, 0, 4480, 1392),
  )

  // A screen to the left of the primary one, at negative coordinates, without a taskbar.
  private val left = TrayScreen(
    bounds = IntRect(-1280, 0, 0, 1024),
    workArea = IntRect(-1280, 0, 0, 1024),
  )

  @Test
  fun workAreaAt_pointerOnTaskbar_isTheWorkAreaOfItsScreen() {
    val area = workAreaAt(IntOffset(1800, 1060), listOf(primary, right))

    assertEquals(primary.workArea, area)
  }

  @Test
  fun workAreaAt_pointerOnAnotherScreen_isThatScreensWorkArea() {
    assertEquals(right.workArea, workAreaAt(IntOffset(4400, 1420), listOf(primary, right)))
    assertEquals(left.workArea, workAreaAt(IntOffset(-10, 1000), listOf(left, primary)))
  }

  @Test
  fun workAreaAt_pointerOnEdgeBetweenScreens_belongsToTheScreenItStarts() {
    assertEquals(right.workArea, workAreaAt(IntOffset(1920, 500), listOf(primary, right)))
  }

  @Test
  fun workAreaAt_pointerOutsideEveryScreen_isTheNearestScreensWorkArea() {
    // Below the primary screen, as on a taskbar AWT counts on neither screen.
    assertEquals(primary.workArea, workAreaAt(IntOffset(900, 1090), listOf(primary, right)))
    // Below the right screen's left half, which reaches lower than the primary one.
    assertEquals(right.workArea, workAreaAt(IntOffset(2000, 1500), listOf(primary, right)))
  }

  @Test
  fun workAreaAt_noScreens_isNull() {
    assertNull(workAreaAt(IntOffset(10, 10), emptyList()))
  }

  @Test
  fun trayMenuRequest_pointerOnATaskbar_opensInTheWorkAreaOfItsScreen() {
    val request = trayMenuRequest(IntOffset(4400, 1420), listOf(primary, right))

    assertEquals(IntOffset(4400, 1420), request?.pointer)
    assertEquals(right.workArea, request?.area)
  }
}
