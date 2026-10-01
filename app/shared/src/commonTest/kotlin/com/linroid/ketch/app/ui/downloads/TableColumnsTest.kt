package com.linroid.ketch.app.ui.downloads

import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TableColumnsTest {
  private val padding = 4.dp

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
    assertEquals(100.dp, layout.widthOf(TableColumn.Speed))
    assertFalse(layout.columns.first { it.column == TableColumn.Size }.visible)
    assertEquals(TableColumn.entries.toSet(), layout.columns.map { it.column }.toSet())
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

    assertEquals(TableColumn.MinWidth, layout.widthOf(TableColumn.Size))
    assertEquals(TableColumn.MaxWidth, layout.widthOf(TableColumn.Source))
  }

  @Test
  fun fromId_unknownDensity_isDefault() {
    assertEquals(RowDensity.Compact, RowDensity.fromId("compact"))
    assertEquals(RowDensity.Default, RowDensity.fromId("tiny"))
    assertEquals(RowDensity.Default, RowDensity.fromId(null))
  }
}
