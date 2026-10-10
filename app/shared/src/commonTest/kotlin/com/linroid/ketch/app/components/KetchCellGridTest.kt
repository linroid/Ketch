package com.linroid.ketch.app.components

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class KetchCellGridTest {
  @Test
  fun cellGridLayout_fixedRows_fillsColumnsAsNeeded() {
    assertEquals(CellGridLayout(columns = 12, rows = 3, columnMajor = true), layout(36, rows = 3))
    assertEquals(CellGridLayout(columns = 2, rows = 3, columnMajor = true), layout(4, rows = 3))
    assertEquals(CellGridLayout(columns = 1, rows = 3, columnMajor = true), layout(0, rows = 3))
  }

  @Test
  fun cellGridLayout_wrapping_fitsWholeCellsInTheWidth() {
    // 12 px cells with 3 px gaps: 20 of them take 297 px, a 21st would need 312.
    assertEquals(CellGridLayout(20, 2, columnMajor = false), layout(30, width = 310f))
    assertEquals(CellGridLayout(21, 2, columnMajor = false), layout(30, width = 312f))
    assertEquals(CellGridLayout(1, 3, columnMajor = false), layout(3, width = 5f))
  }

  @Test
  fun cellGridSize_fixedRowsAndWrapping_leaveNoTrailingGap() {
    assertEquals(Size(42f, 42f), cellGridSize(7, rows = 3, cell = 12f, gap = 3f, maxWidth = 400f))
    assertEquals(Size(100f, 27f), cellGridSize(7, rows = null, cell = 12f, gap = 3f, 100f))
  }

  @Test
  fun cellIndexAt_columnMajor_countsDownEachColumnFirst() {
    val layout = layout(36, rows = 3)

    assertEquals(0, cellIndexAt(Offset(1f, 1f), layout, CELL, GAP, 36))
    assertEquals(1, cellIndexAt(Offset(1f, 16f), layout, CELL, GAP, 36))
    assertEquals(3, cellIndexAt(Offset(16f, 1f), layout, CELL, GAP, 36))
    assertEquals(35, cellIndexAt(Offset(170f, 40f), layout, CELL, GAP, 36))
  }

  @Test
  fun cellIndexAt_rowMajor_countsAlongEachRowFirst() {
    val layout = layout(30, width = 310f)

    assertEquals(1, cellIndexAt(Offset(16f, 1f), layout, CELL, GAP, 30))
    assertEquals(20, cellIndexAt(Offset(1f, 16f), layout, CELL, GAP, 30))
  }

  @Test
  fun cellIndexAt_gap_belongsToTheCellBefore() {
    val layout = layout(30, width = 310f)

    assertEquals(0, cellIndexAt(Offset(13.5f, 1f), layout, CELL, GAP, 30))
    assertEquals(1, cellIndexAt(Offset(15f, 1f), layout, CELL, GAP, 30))
  }

  @Test
  fun cellIndexAt_outsideOrPastTheLastCell_isNull() {
    val layout = layout(4, rows = 3)

    assertNull(cellIndexAt(Offset(-1f, 1f), layout, CELL, GAP, 4))
    assertNull(cellIndexAt(Offset(1f, 50f), layout, CELL, GAP, 4))
    assertNull(cellIndexAt(Offset(40f, 1f), layout, CELL, GAP, 4))
    // Column 1 holds cells 3, 4 and 5; only 3 exists.
    assertNull(cellIndexAt(Offset(16f, 16f), layout, CELL, GAP, 4))
  }

  @Test
  fun cellOrigin_columnMajorAndRowMajor_placeCellsOnTheGrid() {
    assertEquals(Offset(15f, 30f), cellOrigin(5, layout(36, rows = 3), CELL, GAP))
    assertEquals(Offset(75f, 15f), cellOrigin(25, layout(30, width = 310f), CELL, GAP))
  }

  @Test
  fun moveCell_columnMajor_upAndDownStayInTheColumn() {
    val layout = layout(8, rows = 3)

    assertEquals(4, moveCell(3, CellMove.Down, layout, 8))
    assertEquals(3, moveCell(3, CellMove.Up, layout, 8))
    assertEquals(2, moveCell(2, CellMove.Down, layout, 8))
    assertEquals(7, moveCell(4, CellMove.Right, layout, 8))
    assertEquals(4, moveCell(7, CellMove.Left, layout, 8))
    // No cell to the right of 6 in a grid of 8.
    assertEquals(6, moveCell(6, CellMove.Right, layout, 8))
  }

  @Test
  fun moveCell_rowMajor_leftAndRightStayInTheRow() {
    val layout = CellGridLayout(columns = 4, rows = 3, columnMajor = false)

    assertEquals(4, moveCell(0, CellMove.Down, layout, 10))
    assertEquals(3, moveCell(3, CellMove.Right, layout, 10))
    assertEquals(4, moveCell(4, CellMove.Left, layout, 10))
    assertEquals(5, moveCell(4, CellMove.Right, layout, 10))
    assertEquals(9, moveCell(9, CellMove.Down, layout, 10))
  }

  private fun layout(count: Int, rows: Int? = null, width: Float? = null): CellGridLayout =
    cellGridLayout(count, rows, CELL, GAP, width)

  private companion object {
    const val CELL = 12f
    const val GAP = 3f
  }
}
