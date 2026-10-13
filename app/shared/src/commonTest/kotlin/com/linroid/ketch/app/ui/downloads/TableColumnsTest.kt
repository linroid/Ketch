package com.linroid.ketch.app.ui.downloads

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableColumnsTest {
  private val padding = 4.dp

  /** What Name and the columns share in a table 1052 dp wide. */
  private val content = 1052.dp - padding * 2 - TableColumn.StatusDotWidth

  @Test
  fun fit_wideTable_showsEveryDefaultColumn() {
    val columns = TableLayout().fit(1052.dp, padding).map { it.column }

    assertEquals(
      listOf(
        TableColumn.Size,
        TableColumn.Progress,
        TableColumn.Speed,
        TableColumn.Left,
        TableColumn.Added,
        TableColumn.Status
      ),
      columns
    )
  }

  @Test
  fun fit_dockedInspectorWidth_keepsNameSizeProgressSpeedAndLeft() {
    val columns = TableLayout().fit(731.dp, padding).map { it.column }

    assertEquals(
      listOf(TableColumn.Size, TableColumn.Progress, TableColumn.Speed, TableColumn.Left),
      columns
    )
  }

  @Test
  fun fit_narrowingTable_hidesAddedThenStatus() {
    val layout = TableLayout()

    assertTrue(TableColumn.Added in layout.fit(904.dp, padding).map { it.column })
    assertFalse(TableColumn.Added in layout.fit(903.dp, padding).map { it.column })
    assertTrue(TableColumn.Status in layout.fit(800.dp, padding).map { it.column })
    assertFalse(TableColumn.Status in layout.fit(799.dp, padding).map { it.column })
  }

  @Test
  fun fit_tooNarrowForAnything_keepsProgressAndSpeed() {
    val columns = TableLayout().fit(200.dp, padding).map { it.column }

    assertEquals(listOf(TableColumn.Progress, TableColumn.Speed), columns)
  }

  @Test
  fun fit_optionalColumnShown_hidesItBeforeTheDefaults() {
    val layout = TableLayout().withVisible(TableColumn.Source, true)

    val columns = layout.fit(1000.dp, padding).map { it.column }

    assertFalse(TableColumn.Source in columns)
    assertTrue(TableColumn.Added in columns)
  }

  @Test
  fun fit_widenedColumnNarrowed_givesBackItsWidthBeforeColumnsHide() {
    val layout = TableLayout().withWidth(TableColumn.Progress, 300.dp)

    val columns = layout.fit(984.dp, padding)

    assertTrue(TableColumn.Added in columns.map { it.column })
    assertEquals(220.dp, columns.first { it.column == TableColumn.Progress }.width)
  }

  @Test
  fun fit_widenedColumnNarrowedPastDefaults_hidesAsDefaultsWould() {
    val layout = TableLayout().withWidth(TableColumn.Progress, 300.dp)

    val columns = layout.fit(903.dp, padding).map { it.column }

    assertFalse(TableColumn.Added in columns)
    assertTrue(TableColumn.Status in columns)
  }

  @Test
  fun arrange_nameNarrowerThanItsRoom_stretchesTheLastColumn() {
    val layout = TableLayout(nameWidth = 300.dp)

    val geometry = layout.arrange(layout.fit(1052.dp, padding), content)

    assertEquals(300.dp, geometry.name)
    assertEquals(208.dp, geometry.columns.last().width)
    assertEquals(96.dp, geometry.columns.first().width)
  }

  @Test
  fun withDivider_dragged_resizesTheColumnBeforeItAndTheLastGivesTheRoom() {
    val start = TableLayout()
    val auto = start.arrange(start.fit(1052.dp, padding), content)
    assertEquals(0.dp, auto.stretch)

    val named = start.withDivider(auto, index = 0, width = 300.dp)
    val narrowed = named.arrange(named.fit(1052.dp, padding), content)
    assertEquals(300.dp, narrowed.name)
    assertEquals(88.dp, narrowed.stretch)

    val widened = named.withDivider(narrowed, index = 2, width = 180.dp)
    val after = widened.arrange(widened.fit(1052.dp, padding), content)
    assertEquals(300.dp, after.name)
    assertEquals(180.dp, after.columns.first { it.column == TableColumn.Progress }.width)
    assertEquals(48.dp, after.stretch)
  }

  @Test
  fun withDivider_draggedPastTheRoomLeft_stopsWhereTheLastColumnIsAtItsWidth() {
    val layout = TableLayout(nameWidth = 300.dp)
    val geometry = layout.arrange(layout.fit(1052.dp, padding), content)

    val widened = layout.withDivider(geometry, index = 2, width = 400.dp)

    assertEquals(228.dp, widened.columns.first { it.column == TableColumn.Progress }.width)
  }

  @Test
  fun encode_nameWidth_roundTripsThroughDecode() {
    val layout = TableLayout(nameWidth = 300.dp).withWidth(TableColumn.Size, 120.dp)

    assertEquals(layout, TableLayout.decode(layout.encode()))
    assertEquals(null, TableLayout.decode("size,progress").nameWidth)
  }

  @Test
  fun encode_changedLayout_roundTripsThroughDecode() {
    val layout = TableLayout()
      .withWidth(TableColumn.Progress, 180.dp)
      .withVisible(TableColumn.Added, false)
      .withVisible(TableColumn.Origin, true)

    val encoded = layout.encode()

    assertTrue("progress:180" in encoded)
    assertTrue("-added" in encoded)
    assertEquals(layout, TableLayout.decode(encoded))
  }

  @Test
  fun decode_unknownAndMissingColumns_keepsTheRestAtDefaults() {
    val layout = TableLayout.decode("speed:100,bogus,-size")

    assertEquals(TableColumn.Speed, layout.columns.first().column)
    assertEquals(100.dp, layout.columns.first().width)
    assertFalse(layout.columns.first { it.column == TableColumn.Size }.visible)
    assertEquals(TableColumn.entries.toSet(), layout.columns.map { it.column }.toSet())
  }

  @Test
  fun decode_layoutWithoutFinished_appendsItHidden() {
    // A layout saved before the Finished column existed.
    val saved = "size,progress,speed,left,added,status,-connections,-source,-origin,-priority," +
      "-device"

    val layout = TableLayout.decode(saved)

    val finished = layout.columns.last()
    assertEquals(TableColumn.Finished, finished.column)
    assertFalse(finished.visible)
    assertFalse(layout.shown.any { it.column == TableColumn.Finished })
  }

  @Test
  fun decode_hiddenFixedColumn_staysVisible() {
    val layout = TableLayout.decode("-progress,-speed")

    assertTrue(layout.shown.any { it.column == TableColumn.Progress })
    assertTrue(layout.shown.any { it.column == TableColumn.Speed })
  }

  @Test
  fun withWidth_beyondTheLimits_clampsTheWidth() {
    val layout = TableLayout()
      .withWidth(TableColumn.Size, 10.dp)
      .withWidth(TableColumn.Source, 1000.dp)

    val widths = layout.columns.associate { it.column to it.width }
    assertEquals(TableColumn.MinWidth, widths[TableColumn.Size])
    assertEquals(TableColumn.MaxWidth, widths[TableColumn.Source])
  }

  @Test
  fun withWidth_fractionalWidth_keepsWhatDecodeReadsBack() {
    val layout = TableLayout().withWidth(TableColumn.Progress, 180.6.dp)

    assertEquals(181.dp, layout.columns.first { it.column == TableColumn.Progress }.width)
    assertEquals(layout, TableLayout.decode(layout.encode()))
  }

  @Test
  fun fromId_unknownDensity_isDefault() {
    assertEquals(RowDensity.Compact, RowDensity.fromId("compact"))
    assertEquals(RowDensity.Default, RowDensity.fromId("tiny"))
    assertEquals(RowDensity.Default, RowDensity.fromId(null))
  }
}
