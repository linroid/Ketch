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
import kotlin.math.roundToInt

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
 * @property chosen whether the user set [width], which [TableLayout.withHeaderRoom] then keeps
 *   even when it equals the default.
 */
@Immutable
internal data class ColumnSetting(
  val column: TableColumn,
  val visible: Boolean = !column.optional,
  val width: Dp = column.width,
  val chosen: Boolean = false,
)

/**
 * The columns of one tab of the Downloads table after Name, in order, with their widths and
 * whether they show. Saved per tab in `UiPreferences.table` with [encode].
 *
 * @property nameWidth width of Name, cell padding included, once a divider was dragged; `null`
 *   lets Name take what the columns leave. See [arrange].
 */
@Immutable
internal data class TableLayout(
  val columns: List<ColumnSetting> = defaultColumns(),
  val nameWidth: Dp? = null,
) {
  /** The columns the user shows, in order. */
  val shown: List<ColumnSetting>
    get() = columns.filter { it.visible || it.column.fixed }

  /** This layout with [column] shown or hidden; fixed columns always show. */
  fun withVisible(column: TableColumn, visible: Boolean): TableLayout =
    update(column) { it.copy(visible = visible || column.fixed) }

  /**
   * This layout with [column] [width] wide, in whole dp as [encode] saves it, kept between
   * [TableColumn.MinWidth] and MaxWidth.
   */
  fun withWidth(column: TableColumn, width: Dp): TableLayout = update(column) {
    val whole = width.value.roundToInt().dp
    it.copy(width = whole.coerceIn(TableColumn.MinWidth, TableColumn.MaxWidth), chosen = true)
  }

  /**
   * This layout with each column still at its default width widened to [room], the width its
   * header needs, so a header longer than the English one is not cut. Widths the user chose stay.
   */
  fun withHeaderRoom(room: (TableColumn) -> Dp): TableLayout = copy(
    columns = columns.map { setting ->
      if (setting.chosen) return@map setting
      val needed = room(setting.column).coerceAtMost(TableColumn.MaxWidth)
      if (needed > setting.width) setting.copy(width = needed) else setting
    },
  )

  /**
   * The columns that fit a table [available] wide with [padding] on each side, after the status
   * dot column and Name at its minimum width. Columns widened past their own width give that
   * back first, down to [floor] (the width their header needs), each in proportion to how much
   * wider it is; only then do columns hide, in [TableColumn.HideOrder], optional ones first.
   * Progress and Speed always stay.
   */
  fun fit(
    available: Dp,
    padding: Dp,
    floor: (TableColumn) -> Dp = { it.width },
  ): List<ColumnSetting> {
    val fitted = shown.toMutableList()
    val hideable = fitted.filter { it.column.optional }.reversed().map { it.column } +
      TableColumn.HideOrder
    fun narrowest(setting: ColumnSetting) =
      setting.width.coerceAtMost(maxOf(setting.column.width, floor(setting.column)))
    val room = available - padding * 2 - TableColumn.StatusDotWidth - TableColumn.NameMinWidth
    fun narrowestTotal() = fitted.fold(0.dp) { sum, it -> sum + narrowest(it) }
    for (column in hideable) {
      if (narrowestTotal() <= room) break
      fitted.removeAll { it.column == column }
    }
    val over = fitted.fold(0.dp) { sum, it -> sum + it.width } - room
    val extra = fitted.fold(0.dp) { sum, it -> sum + (it.width - narrowest(it)) }
    if (over <= 0.dp || extra <= 0.dp) return fitted
    val kept = 1f - (over / extra).coerceAtMost(1f)
    return fitted.map { setting ->
      val least = narrowest(setting)
      setting.copy(width = least + (setting.width - least) * kept)
    }
  }

  /**
   * The [fitted] columns laid out in [content], the width Name and the columns share: Name
   * [nameWidth] wide, or what the columns leave when `null`, kept between
   * [TableColumn.NameMinWidth] and what leaves the last column its own width, and the last column
   * stretched to the table's end.
   */
  fun arrange(fitted: List<ColumnSetting>, content: Dp): TableGeometry {
    val room = content - fitted.fold(0.dp) { sum, it -> sum + it.width }
    val name = (nameWidth ?: room).coerceAtMost(room).coerceAtLeast(TableColumn.NameMinWidth)
    val stretch = (room - name).coerceAtLeast(0.dp)
    val columns = fitted.mapIndexed { index, setting ->
      if (index == fitted.lastIndex) setting.copy(width = setting.width + stretch) else setting
    }
    return TableGeometry(name, columns, stretch)
  }

  /**
   * This layout with the divider before [geometry]'s column [index] moved so the column before it,
   * Name for 0, is [width] wide. The columns after the divider keep their widths and follow it, as
   * the last column gives or takes the room, never narrower than its own width. Name keeps its
   * width when another column changes.
   */
  fun withDivider(geometry: TableGeometry, index: Int, width: Dp): TableLayout {
    val before = if (index == 0) geometry.name else geometry.columns[index - 1].width
    val min = if (index == 0) TableColumn.NameMinWidth else TableColumn.MinWidth
    val max = if (index == 0) Dp.Infinity else TableColumn.MaxWidth
    val widest = (before + geometry.stretch).coerceAtMost(max).coerceAtLeast(min)
    val target = width.coerceIn(min, widest)
    if (index == 0) return copy(nameWidth = target.value.roundToInt().dp)
    return copy(nameWidth = geometry.name.value.roundToInt().dp)
      .withWidth(geometry.columns[index - 1].column, target)
  }

  /**
   * Encodes this layout as `name:width` when Name has one, then its column ids in order, each
   * followed by `:width` when the user chose it and preceded by `-` when hidden:
   * `name:300,size,progress:160,speed,left,-added`.
   */
  fun encode(): String {
    val name = nameWidth?.let { listOf("$NAME_ID:${it.value.roundToInt()}") }.orEmpty()
    val rest = columns.map { setting ->
      buildString {
        if (!setting.visible && !setting.column.fixed) append('-')
        append(setting.column.id)
        if (setting.chosen) append(':').append(setting.width.value.toInt())
      }
    }
    return (name + rest).joinToString(",")
  }

  private fun update(column: TableColumn, change: (ColumnSetting) -> ColumnSetting): TableLayout =
    copy(columns = columns.map { if (it.column == column) change(it) else it })

  companion object {
    /** Every column at its default width, the optional ones hidden. */
    fun defaultColumns(): List<ColumnSetting> = TableColumn.entries.map(::ColumnSetting)

    /**
     * Decodes [value] made by [encode]. Unknown ids and widths are skipped, and columns it does
     * not name follow at their defaults, so a layout saved by an older version still loads.
     */
    fun decode(value: String?): TableLayout {
      if (value.isNullOrBlank()) return TableLayout()
      val nameWidth = value.split(',').map { it.trim() }
        .firstOrNull { it.substringBefore(':') == NAME_ID }
        ?.substringAfter(':', "")?.toIntOrNull()?.dp
        ?.coerceAtLeast(TableColumn.NameMinWidth)
      val read = value.split(',').mapNotNull { part ->
        val entry = part.trim()
        val hidden = entry.startsWith('-')
        val id = entry.removePrefix("-").substringBefore(':')
        val column = TableColumn.fromId(id) ?: return@mapNotNull null
        val width = entry.substringAfter(':', "").toIntOrNull()?.dp
          ?.coerceIn(TableColumn.MinWidth, TableColumn.MaxWidth)
        ColumnSetting(
          column = column,
          visible = !hidden || column.fixed,
          width = width ?: column.width,
          chosen = width != null,
        )
      }.distinctBy { it.column }
      val missing = TableColumn.entries.filter { column -> read.none { it.column == column } }
      return TableLayout(read + missing.map(::ColumnSetting), nameWidth)
    }

    /** The id [encode] saves Name's width under; older versions skip it as unknown. */
    private const val NAME_ID = "name"
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

/**
 * A table's columns as laid out by [TableLayout.arrange].
 *
 * @property name width of Name, cell padding included.
 * @property columns the columns after Name, the last one stretched to the table's end.
 * @property stretch how much wider than its own width the last column is.
 */
@Immutable
internal data class TableGeometry(
  val name: Dp,
  val columns: List<ColumnSetting>,
  val stretch: Dp,
)
