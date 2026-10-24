package com.linroid.ketch.app.components

import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntRect
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.window.PopupPositionProvider
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class KetchContextMenuTest {
  // The work area of a 1920 × 1080 screen with a 48-point taskbar along its bottom edge.
  private val workArea = IntRect(0, 0, 1920, 1032)

  private val menu = IntSize(240, 400)

  @Test
  fun contextMenuOrigin_taskbarBelow_opensAboveItLeftOfThePointerAtTheRightEdge() {
    val origin = contextMenuOrigin(IntOffset(1800, 1060), menu, workArea, MARGIN)

    // Its right edge at the pointer, its bottom edge MARGIN above the taskbar.
    assertEquals(IntOffset(1560, 1032 - MARGIN - 400), origin)
  }

  @Test
  fun contextMenuOrigin_taskbarBelow_opensRightOfThePointerWhereItFits() {
    val origin = contextMenuOrigin(IntOffset(300, 1060), menu, workArea, MARGIN)

    assertEquals(IntOffset(300, 1032 - MARGIN - 400), origin)
  }

  @Test
  fun contextMenuOrigin_taskbarAbove_opensBelowIt() {
    val area = IntRect(0, 48, 1920, 1080)

    val origin = contextMenuOrigin(IntOffset(1800, 20), menu, area, MARGIN)

    assertEquals(IntOffset(1560, 48 + MARGIN), origin)
  }

  @Test
  fun contextMenuOrigin_taskbarOnTheLeft_opensRightOfIt() {
    val area = IntRect(64, 0, 1920, 1080)

    val origin = contextMenuOrigin(IntOffset(30, 1000), menu, area, MARGIN)

    // Above the pointer, as there is no room below it.
    assertEquals(IntOffset(64 + MARGIN, 600), origin)
  }

  @Test
  fun contextMenuOrigin_taskbarOnTheRight_opensLeftOfIt() {
    val area = IntRect(0, 0, 1856, 1080)

    val origin = contextMenuOrigin(IntOffset(1890, 1000), menu, area, MARGIN)

    assertEquals(IntOffset(1856 - MARGIN - 240, 600), origin)
  }

  @Test
  fun contextMenuOrigin_pointerInsideTheArea_opensAtThePointer() {
    // As for an icon in the flyout of hidden icons, above the taskbar.
    val origin = contextMenuOrigin(IntOffset(900, 300), menu, workArea, MARGIN)

    assertEquals(IntOffset(900, 300), origin)
  }

  @Test
  fun contextMenuOrigin_areaWithOffsetOrigin_staysInIt() {
    // The work area of a screen right of the primary one.
    val area = IntRect(1920, 0, 4480, 1392)

    val origin = contextMenuOrigin(IntOffset(4400, 1420), menu, area, MARGIN)

    assertEquals(IntOffset(4160, 1392 - MARGIN - 400), origin)
  }

  @Test
  fun contextMenuOrigin_menuTallerThanTheArea_startsAtItsTopWithoutMargin() {
    val area = IntRect(0, 0, 1920, 300)

    val origin = contextMenuOrigin(IntOffset(1800, 320), menu, area, MARGIN)

    assertEquals(IntOffset(1560, 0), origin)
  }

  @Test
  fun contextMenuOrigin_areaTooSmallForTheMargins_fitsFlushWithItsEdges() {
    // 10 points of room across: not enough for a margin on both sides.
    val area = IntRect(0, 0, 250, 1032)

    // Neither right nor left of the pointer fits, so it moves to the right edge.
    val origin = contextMenuOrigin(IntOffset(120, 1060), menu, area, MARGIN)

    assertEquals(IntOffset(10, 1032 - MARGIN - 400), origin)
  }

  @Test
  fun contextMenuOrigin_rightToLeft_opensLeftOfThePointerFirst() {
    val origin = contextMenuOrigin(
      point = IntOffset(900, 300),
      size = menu,
      area = workArea,
      margin = MARGIN,
      layoutDirection = LayoutDirection.Rtl,
    )

    assertEquals(IntOffset(660, 300), origin)
  }

  @Test
  fun contextMenuPosition_turnedLeftOfThePointer_turnsItsSubmenusLeftToo() {
    val root = MenuLevel()

    place(ContextMenuPositionProvider(IntOffset(1800, 1060), MARGIN, root), menu)

    assertTrue(root.towardStart)
  }

  @Test
  fun contextMenuPosition_openedRightOfThePointer_leavesSubmenusOnTheEndSide() {
    val root = MenuLevel()

    place(ContextMenuPositionProvider(IntOffset(300, 1060), MARGIN, root), menu)

    assertFalse(root.towardStart)
  }

  @Test
  fun submenuPosition_parentTurnedLeft_opensLeftWhereBothSidesFit() {
    val parent = MenuLevel().apply { towardStart = true }
    val child = MenuLevel()

    val origin = place(SubmenuPositionProvider(INSET, MARGIN, parent, child), SUBMENU, ROW)

    assertEquals(ROW.left - INSET - SUBMENU.width, origin.x)
    assertTrue(child.towardStart)
  }

  @Test
  fun submenuPosition_parentDidNotTurn_opensOnTheEndSideWhereBothSidesFit() {
    val parent = MenuLevel()
    val child = MenuLevel()

    val origin = place(SubmenuPositionProvider(INSET, MARGIN, parent, child), SUBMENU, ROW)

    assertEquals(ROW.right + INSET, origin.x)
    assertFalse(child.towardStart)
  }

  @Test
  fun submenuPosition_parentTurnedLeftWithoutRoomThere_opensRightAndTurnsBack() {
    val parent = MenuLevel().apply { towardStart = true }
    val child = MenuLevel()
    val row = IntRect(100, 500, 400, 532)

    val origin = place(SubmenuPositionProvider(INSET, MARGIN, parent, child), SUBMENU, row)

    assertEquals(row.right + INSET, origin.x)
    assertFalse(child.towardStart)
  }

  // Places a popup of [size] for [anchor] in the work area's window, left to right.
  private fun place(
    provider: PopupPositionProvider,
    size: IntSize,
    anchor: IntRect = workArea,
  ): IntOffset = provider.calculatePosition(
    anchorBounds = anchor,
    windowSize = IntSize(workArea.width, workArea.height),
    layoutDirection = LayoutDirection.Ltr,
    popupContentSize = size,
  )

  private companion object {
    const val MARGIN = 8
    const val INSET = 5
    val SUBMENU = IntSize(260, 200)

    // A row in the middle of the screen, with room for the submenu on either side.
    val ROW = IntRect(800, 500, 1100, 532)
  }
}
