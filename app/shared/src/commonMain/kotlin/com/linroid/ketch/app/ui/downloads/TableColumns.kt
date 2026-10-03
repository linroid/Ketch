package com.linroid.ketch.app.ui.downloads

import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.coerceIn
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.state.SortKey
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.downloads_density_compact
import ketch.app.shared.generated.resources.downloads_density_default
import ketch.app.shared.generated.resources.downloads_table_connections_short
import ketch.app.shared.generated.resources.sort_added
import ketch.app.shared.generated.resources.sort_device
import ketch.app.shared.generated.resources.sort_finished
import ketch.app.shared.generated.resources.sort_origin
import ketch.app.shared.generated.resources.sort_priority
import ketch.app.shared.generated.resources.sort_progress
import ketch.app.shared.generated.resources.sort_size
import ketch.app.shared.generated.resources.sort_source
import ketch.app.shared.generated.resources.sort_speed
import ketch.app.shared.generated.resources.sort_status
import ketch.app.shared.generated.resources.sort_time_left
import org.jetbrains.compose.resources.StringResource

/**
 * A column of the Downloads table after its Name column, in the order the table shows them by
 * default.
 *
 * @property id name used in [TableLayout.encode].
 * @property width default width, cell padding included.
 * @property sort what clicking the header sorts by.
 * @property optional whether the column starts hidden.
 * @property numeric whether the cells are numbers, right-aligned.
 * @property fixed whether the column is always shown and never hides for room.
 */
internal enum class TableColumn(
  val id: String,
  private val header: StringResource,
  val width: Dp,
  val sort: SortKey,
  val optional: Boolean = false,
  val numeric: Boolean = false,
  val fixed: Boolean = false,
) {
  Size("size", Res.string.sort_size, 96.dp, SortKey.Size, numeric = true),
  Progress("progress", Res.string.sort_progress, 140.dp, SortKey.Progress, fixed = true),
  Speed("speed", Res.string.sort_speed, 92.dp, SortKey.Speed, numeric = true, fixed = true),
  Left("left", Res.string.sort_time_left, 76.dp, SortKey.TimeLeft, numeric = true),
  Added("added", Res.string.sort_added, 104.dp, SortKey.Added),
  Finished("finished", Res.string.sort_finished, 104.dp, SortKey.Finished, optional = true),
  Status("status", Res.string.sort_status, 120.dp, SortKey.Status),
  Connections(
    "connections",
    Res.string.downloads_table_connections_short,
    72.dp,
    SortKey.Connections,
    optional = true,
  ),
  Source("source", Res.string.sort_source, 128.dp, SortKey.Source, optional = true),
  Origin("origin", Res.string.sort_origin, 88.dp, SortKey.Origin, optional = true),
  Priority("priority", Res.string.sort_priority, 72.dp, SortKey.Priority, optional = true),
  Device("device", Res.string.sort_device, 128.dp, SortKey.Device, optional = true);

  /** The header label, such as "Size" or "Conn.". */
  val label: UiText
    get() = header.text()

  /** Whether the reason of a waiting, failed or finished row spans this column. */
  val inReasonSpan: Boolean
    get() = this == Progress || this == Speed || this == Left

  companion object {
    /** Width of the status dot column, before Name. */
    val StatusDotWidth: Dp = 28.dp

    /** Narrowest the Name column gets before other columns hide. */
    val NameMinWidth: Dp = 240.dp

    /** Narrowest a column can be dragged to. */
    val MinWidth: Dp = 56.dp

    /** Widest a column can be dragged to. */
    val MaxWidth: Dp = 320.dp

    /** Narrowest table; below it the page shows list rows. */
    val TableMinWidth: Dp = 720.dp

    /**
     * Columns that hide, in this order, when the table is too narrow: optional ones first from
     * the last, then Added, Status, Left and Size.
     */
    internal val HideOrder: List<TableColumn> = listOf(Added, Status, Left, Size)

    /** The column whose [id] is [value]. */
    fun fromId(value: String): TableColumn? = entries.firstOrNull { it.id == value }
  }
}

/**
 * One column of a [TableLayout].
 *
 * @property visible whether the user shows it; it may still hide when the table is narrow.
 * @property width its width, cell padding included.
 */
@Immutable
internal data class ColumnSetting(
  val column: TableColumn,
  val visible: Boolean = !column.optional,
  val width: Dp = column.width,
)

/**
 * The columns of one tab of the Downloads table after Name, in order, with their widths and
 * whether they show. Saved per tab in `UiPreferences.table` with [encode].
 */
@Immutable
internal data class TableLayout(val columns: List<ColumnSetting> = defaultColumns()) {
  /** The columns the user shows, in order. */
  val shown: List<ColumnSetting>
    get() = columns.filter { it.visible || it.column.fixed }

  /** This layout with [column] shown or hidden; fixed columns always show. */
  fun withVisible(column: TableColumn, visible: Boolean): TableLayout =
    update(column) { it.copy(visible = visible || column.fixed) }

  /** This layout with [column] [width] wide, kept between [TableColumn.MinWidth] and MaxWidth. */
  fun withWidth(column: TableColumn, width: Dp): TableLayout =
    update(column) { it.copy(width = width.coerceIn(TableColumn.MinWidth, TableColumn.MaxWidth)) }

  /**
   * This layout with each column still at its default width widened to [room], the width its
   * header needs, so a header longer than the English one is not cut. Widths the user chose stay.
   */
  fun withHeaderRoom(room: (TableColumn) -> Dp): TableLayout = TableLayout(
    columns.map { setting ->
      if (setting.width != setting.column.width) return@map setting
      val needed = room(setting.column).coerceAtMost(TableColumn.MaxWidth)
      if (needed > setting.width) setting.copy(width = needed) else setting
    },
  )

  /**
   * The columns that fit a table [available] wide with [padding] on each side, after the status
   * dot column and Name at its minimum width. Columns hide in [TableColumn.HideOrder], optional
   * ones first; Progress and Speed always stay.
   */
  fun fit(available: Dp, padding: Dp): List<ColumnSetting> {
    val fitted = shown.toMutableList()
    val hideable = fitted.filter { it.column.optional }.reversed().map { it.column } +
      TableColumn.HideOrder
    fun needed() = padding * 2 + TableColumn.StatusDotWidth + TableColumn.NameMinWidth +
      fitted.fold(0.dp) { sum, it -> sum + it.width }
    for (column in hideable) {
      if (needed() <= available) break
      fitted.removeAll { it.column == column }
    }
    return fitted
  }

  /**
   * Encodes this layout as its column ids in order, each followed by `:width` when not the
   * default and preceded by `-` when hidden: `size,progress:160,speed,left,-added`.
   */
  fun encode(): String = columns.joinToString(",") { setting ->
    buildString {
      if (!setting.visible && !setting.column.fixed) append('-')
      append(setting.column.id)
      if (setting.width != setting.column.width) append(':').append(setting.width.value.toInt())
    }
  }

  private fun update(column: TableColumn, change: (ColumnSetting) -> ColumnSetting): TableLayout =
    TableLayout(columns.map { if (it.column == column) change(it) else it })

  companion object {
    /** Every column at its default width, the optional ones hidden. */
    fun defaultColumns(): List<ColumnSetting> = TableColumn.entries.map(::ColumnSetting)

    /**
     * Decodes [value] made by [encode]. Unknown ids and widths are skipped, and columns it does
     * not name follow at their defaults, so a layout saved by an older version still loads.
     */
    fun decode(value: String?): TableLayout {
      if (value.isNullOrBlank()) return TableLayout()
      val read = value.split(',').mapNotNull { part ->
        val entry = part.trim()
        val hidden = entry.startsWith('-')
        val id = entry.removePrefix("-").substringBefore(':')
        val column = TableColumn.fromId(id) ?: return@mapNotNull null
        val width = entry.substringAfter(':', "").toIntOrNull()?.dp
          ?.coerceIn(TableColumn.MinWidth, TableColumn.MaxWidth) ?: column.width
        ColumnSetting(column, visible = !hidden || column.fixed, width = width)
      }.distinctBy { it.column }
      val missing = TableColumn.entries.filter { column -> read.none { it.column == column } }
      return TableLayout(read + missing.map(::ColumnSetting))
    }
  }
}

/**
 * How tall the table's rows are, chosen in the "⋯" menu.
 *
 * @property id value saved under [ROWS_KEY] in `UiPreferences.table`.
 */
internal enum class RowDensity(val id: String, private val resource: StringResource) {
  Default("default", Res.string.downloads_density_default),
  Compact("compact", Res.string.downloads_density_compact);

  /** The menu label. */
  val label: UiText
    get() = resource.text()

  companion object {
    /** Key of the row density in `UiPreferences.table`, next to the tabs' column layouts. */
    const val ROWS_KEY: String = "rows"

    /** The density saved as [value], or [Default]. */
    fun fromId(value: String?): RowDensity = entries.firstOrNull { it.id == value } ?: Default
  }
}
