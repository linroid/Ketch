package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.util.clockTime
import com.linroid.ketch.app.util.formatBytes
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.TimeZone
import kotlinx.datetime.minus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * What the Downloads list sorts by: a column, or [Smart] order.
 *
 * @property id name used in [ListArrangement.encode].
 * @property label column or menu label.
 * @property descendingFirst whether the first click on the column sorts high to low.
 */
enum class SortKey(val id: String, val label: String, val descendingFirst: Boolean) {
  /**
   * Downloading first by priority then progress, then waiting tasks in start order, paused,
   * failed and canceled, and finished tasks, the newest first.
   */
  Smart("smart", "Smart", descendingFirst = false),
  Name("name", "Name", descendingFirst = false),
  Size("size", "Size", descendingFirst = true),
  Progress("progress", "Progress", descendingFirst = true),

  /** Speed averaged over the last 3 samples, so rows do not swap on every tick. */
  Speed("speed", "Speed", descendingFirst = true),
  TimeLeft("left", "Left", descendingFirst = false),
  Added("added", "Added", descendingFirst = true),
  Status("status", "Status", descendingFirst = false),
  Connections("connections", "Connections", descendingFirst = true),
  Source("source", "Source", descendingFirst = false),
  Origin("origin", "Origin", descendingFirst = false),
  Priority("priority", "Priority", descendingFirst = true),
  Device("device", "Device", descendingFirst = false),
}

/**
 * How the Downloads list groups rows under headers.
 *
 * @property id name used in [ListArrangement.encode].
 * @property label menu label.
 */
enum class GroupBy(val id: String, val label: String) {
  /**
   * Downloading, waiting, paused, needing attention, then finished tasks by the day they were
   * added: today, yesterday, this week and earlier.
   */
  Smart("smart", "Smart"),
  Status("status", "Status"),
  Day("day", "Day"),
  Device("device", "Device"),
  Site("site", "Site"),
  Type("type", "Type"),
  None("none", "None"),
}

/**
 * Sort order and grouping of one tab of the Downloads list.
 *
 * @property sort what rows are sorted by inside each group.
 * @property descending whether [sort] runs high to low; ignored by [SortKey.Smart].
 * @property group how rows are grouped.
 */
data class ListArrangement(
  val sort: SortKey = SortKey.Smart,
  val descending: Boolean = false,
  val group: GroupBy = GroupBy.Smart,
) {
  /**
   * The arrangement after clicking the [key] column header: the same column reverses, another
   * column sorts in its usual direction.
   */
  fun sortedBy(key: SortKey): ListArrangement = if (key == sort) {
    copy(descending = !descending)
  } else {
    copy(sort = key, descending = key.descendingFirst)
  }

  /** Encodes this arrangement for `UiPreferences.sort`, such as `speed:desc:smart`. */
  fun encode(): String = "${sort.id}:${if (descending) DESC else ASC}:${group.id}"

  companion object {
    private const val ASC = "asc"
    private const val DESC = "desc"

    /** The arrangement a [filter] tab starts with: Smart groups on All, none elsewhere. */
    fun default(filter: StatusFilter): ListArrangement =
      ListArrangement(group = if (filter == StatusFilter.All) GroupBy.Smart else GroupBy.None)

    /**
     * Decodes [value] made by [encode], falling back to the [filter] tab's default for any part
     * that is missing or unknown.
     */
    fun decode(value: String?, filter: StatusFilter): ListArrangement {
      val fallback = default(filter)
      val parts = value?.split(':') ?: return fallback
      val sort = SortKey.entries.firstOrNull { it.id == parts.getOrNull(0) } ?: fallback.sort
      val descending = when (parts.getOrNull(1)) {
        DESC -> true
        ASC -> false
        else -> sort.descendingFirst
      }
      val group = GroupBy.entries.firstOrNull { it.id == parts.getOrNull(2) } ?: fallback.group
      return ListArrangement(sort, descending, group)
    }
  }
}

/**
 * Rows under one header of the Downloads list.
 *
 * @property id stable identity of the group, such as `smart:downloading` or `site:github.com`,
 *   for collapse state and list keys.
 * @property title header title, such as "Downloading" or "Added today"; empty when the list is
 *   not grouped.
 * @property rows the rows in display order.
 * @property details what the header says after the title, such as the count, the total speed
 *   and when all downloads finish: `["2", "9.1 MB/s", "all done ≈ 14:32"]`.
 * @property collapsedByDefault whether the group starts collapsed, as an "Added earlier" group
 *   with more than [COLLAPSE_ABOVE] rows does.
 */
data class RowGroup(
  val id: String,
  val title: String,
  val rows: List<TaskRow>,
  val details: List<String> = emptyList(),
  val collapsedByDefault: Boolean = false,
) {
  companion object {
    /** An "Added earlier" group with more rows than this starts collapsed. */
    const val COLLAPSE_ABOVE: Int = 50
  }
}

/**
 * Groups [rows] and sorts each group as [arrangement] says. Dates are local to [timeZone] at
 * [now].
 */
fun arrangeRows(
  rows: List<TaskRow>,
  arrangement: ListArrangement,
  now: Instant,
  timeZone: TimeZone,
): List<RowGroup> = sortedSlots(rows, arrangement, now, timeZone)
  .map { (slot, members) -> groupOf(slot, members, arrangement, now, timeZone) }

/**
 * Keeps the Downloads list from shuffling under the pointer: rows are re-sorted at most every
 * [interval], and not at all while [arrange] is told the list is frozen (the pointer is over
 * it, a row has focus or a menu is open). Between re-sorts, rows keep their group and place,
 * rows that left disappear and new rows are placed where a full sort would put them among the
 * others.
 *
 * Not thread-safe: call it from one coroutine.
 *
 * @param interval shortest time between two re-sorts.
 */
class StableArrangement(private val interval: Duration = RESORT_INTERVAL) {
  private var layout: List<Pair<Slot, List<TaskKey>>> = emptyList()
  private var view: Any? = null
  private var arrangedAt: Instant? = null

  /**
   * Whether the last [arrange] kept an earlier order instead of sorting, so arranging the same
   * rows again once the list may re-sort can move them.
   */
  var isHeld: Boolean = false
    private set

  /**
   * Arranges [rows] like [arrangeRows], keeping the previous order unless the list may re-sort.
   *
   * @param frozen whether the user is pointing at or working in the list.
   * @param view what the rows are, such as the tab, search and [arrangement]; when it changes
   *   the rows are sorted at once, frozen or not.
   */
  fun arrange(
    rows: List<TaskRow>,
    arrangement: ListArrangement,
    now: Instant,
    timeZone: TimeZone,
    frozen: Boolean = false,
    view: Any? = arrangement,
  ): List<RowGroup> {
    val last = arrangedAt
    // A clock set back counts as due, so the order is not held until the clock catches up.
    val due = last == null || now < last || now - last >= interval
    val held = if (view == this.view && (!due || frozen)) {
      keep(rows, arrangement, now, timeZone)
    } else {
      null
    }
    if (held == null) {
      layout = sortedSlots(rows, arrangement, now, timeZone)
        .map { (slot, members) -> slot to members.map { it.key } }
      this.view = view
      arrangedAt = now
    } else {
      layout = held
    }
    isHeld = held != null
    val rowsByKey = rows.associateBy { it.key }
    return layout.map { (slot, keys) ->
      groupOf(slot, keys.map(rowsByKey::getValue), arrangement, now, timeZone)
    }
  }

  /**
   * The previous layout without the rows that left and with new rows placed among the others,
   * or `null` when none of [rows] was shown before, since sorting them then moves nothing.
   */
  private fun keep(
    rows: List<TaskRow>,
    arrangement: ListArrangement,
    now: Instant,
    timeZone: TimeZone,
  ): List<Pair<Slot, List<TaskKey>>>? {
    val present = rows.mapTo(HashSet()) { it.key }
    val groups = LinkedHashMap<Slot, MutableList<TaskKey>>()
    for ((slot, keys) in layout) {
      val kept = keys.filterTo(ArrayList()) { it in present }
      if (kept.isNotEmpty()) groups[slot] = kept
    }
    if (groups.isEmpty()) return null
    val placed = groups.values.flatMapTo(HashSet()) { it }
    val fresh = rows.filter { it.key !in placed }
    if (fresh.isNotEmpty()) {
      val order = rowOrder(arrangement)
      val rowsByKey = rows.associateBy { it.key }
      for (row in fresh.sortedWith(order)) {
        val keys = groups.getOrPut(slotOf(row, arrangement.group, now, timeZone)) { ArrayList() }
        val index = keys.indexOfFirst { order.compare(rowsByKey.getValue(it), row) > 0 }
        keys.add(if (index < 0) keys.size else index, row.key)
      }
    }
    return groups.entries.sortedWith(compareBy(SLOT_ORDER) { it.key }).map { it.key to it.value }
  }

  companion object {
    /** Shortest time between two re-sorts of a live list. */
    val RESORT_INTERVAL: Duration = 2.seconds
  }
}

private enum class SlotKind { Downloading, Waiting, Day, Plain }

/**
 * Where a row goes under a [GroupBy], identified by [id] alone, so a device renamed while the
 * order is held still has one group. Groups are ordered by [order], then by [name], then by
 * first appearance.
 */
private class Slot(
  val id: String,
  val title: String,
  val order: Int,
  val name: String = "",
  val kind: SlotKind = SlotKind.Plain,
  val collapsible: Boolean = false,
) {
  override fun equals(other: Any?): Boolean = other is Slot && other.id == id

  override fun hashCode(): Int = id.hashCode()
}

private val SLOT_ORDER: Comparator<Slot> = compareBy({ it.order }, { it.name })

private val DOWNLOADING = Slot("smart:downloading", "Downloading", 0, kind = SlotKind.Downloading)
private val WAITING = Slot("smart:waiting", "Waiting", 1, kind = SlotKind.Waiting)
private val PAUSED = Slot("smart:paused", "Paused", 2)
private val ATTENTION = Slot("smart:attention", "Needs attention", 3)
private val ALL = Slot("all", "", 0)

/** [rows] grouped as [arrangement] says, groups in order and rows sorted inside each. */
private fun sortedSlots(
  rows: List<TaskRow>,
  arrangement: ListArrangement,
  now: Instant,
  timeZone: TimeZone,
): List<Pair<Slot, List<TaskRow>>> {
  val order = rowOrder(arrangement)
  return rows.groupBy { slotOf(it, arrangement.group, now, timeZone) }
    .entries
    .sortedWith(compareBy(SLOT_ORDER) { it.key })
    .map { it.key to it.value.sortedWith(order) }
}

private fun slotOf(row: TaskRow, group: GroupBy, now: Instant, timeZone: TimeZone): Slot =
  when (group) {
    GroupBy.Smart -> when (row.state) {
      is DownloadState.Downloading -> DOWNLOADING
      is DownloadState.Queued, is DownloadState.Scheduled -> WAITING
      is DownloadState.Paused -> PAUSED
      is DownloadState.Failed, is DownloadState.Canceled -> ATTENTION
      is DownloadState.Completed -> daySlot("smart", row.createdAt, now, timeZone)
    }
    GroupBy.Status -> {
      val filter = StatusFilter.entries.first { it != StatusFilter.All && it.matches(row.state) }
      val kind = when (filter) {
        StatusFilter.Downloading -> SlotKind.Downloading
        StatusFilter.Waiting -> SlotKind.Waiting
        else -> SlotKind.Plain
      }
      Slot("status:${filter.name.lowercase()}", filter.label, filter.ordinal, kind = kind)
    }
    GroupBy.Day -> daySlot("day", row.createdAt, now, timeZone)
    GroupBy.Device -> Slot("device:${row.key.deviceId}", row.device.name, 0)
    GroupBy.Site -> row.sourceHost?.let { Slot("site:$it", it, 0, name = it) }
      ?: Slot("site:", "Other", 1)
    GroupBy.Type -> Slot("type:${row.fileType.id}", row.fileType.label, row.fileType.ordinal)
    GroupBy.None -> ALL
  }

/** Today, yesterday, the rest of the last 7 days, and earlier, after the smart groups. */
private fun daySlot(prefix: String, createdAt: Instant, now: Instant, timeZone: TimeZone): Slot {
  val today = now.toLocalDateTime(timeZone).date
  val date = createdAt.toLocalDateTime(timeZone).date
  return when {
    date >= today -> Slot("$prefix:today", "Added today", 4, kind = SlotKind.Day)
    date == today.minus(1, DateTimeUnit.DAY) ->
      Slot("$prefix:yesterday", "Added yesterday", 5, kind = SlotKind.Day)
    date > today.minus(7, DateTimeUnit.DAY) ->
      Slot("$prefix:week", "Added this week", 6, kind = SlotKind.Day)
    else -> Slot("$prefix:earlier", "Added earlier", 7, collapsible = true)
  }
}

private fun groupOf(
  slot: Slot,
  rows: List<TaskRow>,
  arrangement: ListArrangement,
  now: Instant,
  timeZone: TimeZone,
): RowGroup {
  val details = buildList {
    add(rows.size.toString())
    when (slot.kind) {
      SlotKind.Downloading -> {
        val speed = rows.sumOf { row ->
          (row.state as? DownloadState.Downloading)?.progress?.bytesPerSecond ?: 0L
        }
        add("${formatBytes(speed)}/s")
        val left = rows.mapNotNull { it.timeLeft }
        if (left.isNotEmpty() && left.size == rows.size) {
          add("all done ≈ ${clockTime(now + left.max(), timeZone)}")
        }
      }
      SlotKind.Waiting -> if (arrangement.sort == SortKey.Smart) add("in start order")
      SlotKind.Day -> {
        val size = rows.sumOf { it.sizeBytes ?: 0L }
        if (size > 0) add(formatBytes(size))
      }
      SlotKind.Plain -> Unit
    }
  }
  return RowGroup(
    id = slot.id,
    title = slot.title,
    rows = rows,
    details = details,
    collapsedByDefault = slot.collapsible && rows.size > RowGroup.COLLAPSE_ABOVE,
  )
}

private fun rowOrder(arrangement: ListArrangement): Comparator<TaskRow> {
  val descending = arrangement.descending
  val order = when (arrangement.sort) {
    SortKey.Smart -> SMART_ORDER
    SortKey.Name -> textOrder(descending) { it.name }
    SortKey.Size -> valueOrder(descending) { it.sizeBytes }
    SortKey.Progress -> valueOrder(descending) { it.progress }
    SortKey.Speed -> valueOrder(descending) { it.speed }
    SortKey.TimeLeft -> valueOrder(descending) { it.timeLeft }
    SortKey.Added -> valueOrder(descending) { it.createdAt }
    SortKey.Status -> valueOrder(descending) { smartRank(it.state) }
    SortKey.Connections -> valueOrder(descending) { it.connections }
    SortKey.Source -> textOrder(descending) { it.sourceHost }
    SortKey.Origin -> textOrder(descending) { it.origin?.label }
    SortKey.Priority -> valueOrder(descending) { it.request.priority }
    SortKey.Device -> textOrder(descending) { it.device.name }
  }
  return order.then(TIEBREAK)
}

/** Rank of a state in Smart order. */
private fun smartRank(state: DownloadState): Int = when (state) {
  is DownloadState.Downloading -> 0
  is DownloadState.Queued, is DownloadState.Scheduled -> 1
  is DownloadState.Paused -> 2
  is DownloadState.Failed, is DownloadState.Canceled -> 3
  is DownloadState.Completed -> 4
}

/** When a scheduled task is due to start; `null` when it waits only for conditions. */
private fun startTime(row: TaskRow): Instant? =
  when (val schedule = (row.state as? DownloadState.Scheduled)?.schedule) {
    is DownloadSchedule.AtTime -> schedule.startAt
    is DownloadSchedule.AfterDelay -> row.createdAt + schedule.delay
    else -> null
  }

private val NEWEST_FIRST: Comparator<TaskRow> = compareByDescending { it.createdAt }

private val TIEBREAK: Comparator<TaskRow> =
  NEWEST_FIRST.thenBy { it.key.deviceId }.thenBy { it.key.taskId }

/** Queued tasks by priority then age, as the engine starts them, then scheduled ones by time. */
private val WAITING_ORDER: Comparator<TaskRow> = Comparator { a, b ->
  val aScheduled = a.state is DownloadState.Scheduled
  val bScheduled = b.state is DownloadState.Scheduled
  when {
    aScheduled != bScheduled -> if (aScheduled) 1 else -1
    aScheduled -> nullsLast(startTime(a), startTime(b), descending = false)
    else -> compareValuesBy(a, b, { -it.request.priority.ordinal }, { it.createdAt })
  }
}

private val DOWNLOADING_ORDER: Comparator<TaskRow> =
  compareByDescending<TaskRow> { it.request.priority }.then(valueOrder(true) { it.progress })

private val SMART_ORDER: Comparator<TaskRow> = Comparator { a, b ->
  val rank = smartRank(a.state).compareTo(smartRank(b.state))
  when {
    rank != 0 -> rank
    a.state is DownloadState.Downloading -> DOWNLOADING_ORDER.compare(a, b)
    smartRank(a.state) == 1 -> WAITING_ORDER.compare(a, b)
    else -> NEWEST_FIRST.compare(a, b)
  }
}

/** Orders by [selector], keeping rows without a value last in either direction. */
private fun <T : Comparable<T>> valueOrder(
  descending: Boolean,
  selector: (TaskRow) -> T?,
): Comparator<TaskRow> = Comparator { a, b -> nullsLast(selector(a), selector(b), descending) }

/** Orders by [selector] ignoring case, keeping rows without a value last in either direction. */
private fun textOrder(descending: Boolean, selector: (TaskRow) -> String?): Comparator<TaskRow> =
  Comparator { a, b ->
    val x = selector(a)
    val y = selector(b)
    when {
      x == null -> if (y == null) 0 else 1
      y == null -> -1
      descending -> y.compareTo(x, ignoreCase = true)
      else -> x.compareTo(y, ignoreCase = true)
    }
  }

private fun <T : Comparable<T>> nullsLast(x: T?, y: T?, descending: Boolean): Int = when {
  x == null -> if (y == null) 0 else 1
  y == null -> -1
  descending -> y.compareTo(x)
  else -> x.compareTo(y)
}
