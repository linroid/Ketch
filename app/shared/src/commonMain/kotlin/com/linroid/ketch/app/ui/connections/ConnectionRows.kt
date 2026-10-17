package com.linroid.ketch.app.ui.connections

import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.app.state.ConnectionCell
import com.linroid.ketch.app.state.ConnectionGridState
import com.linroid.ketch.app.ui.pulse.endpoint

/**
 * One connection as the Connections page lists it.
 *
 * @property cell the connection, with its task and traffic.
 * @property taskName the task's name in the Downloads list; `null` when the list does not show it.
 * @property deviceName the name of the device it belongs to.
 */
internal class ConnectionRow(
  val cell: ConnectionCell,
  val taskName: String?,
  val deviceName: String,
) {
  val connection get() = cell.connection

  /** Where it goes, as `host:port`. */
  val endpoint: String = cell.endpoint

  /** Its protocol, such as `HTTP/2` or `BitTorrent`. */
  val protocol: String = connection.protocol ?: connection.source.uppercase()

  /** A torrent peer's client, as the peer names it; `null` when unknown or not a peer. */
  val client: String? get() = connection.peer?.client
}

/** Every connection of [grid], each with its task's and device's names. */
internal fun connectionRows(
  grid: ConnectionGridState,
  deviceNames: Map<String, String>,
): List<ConnectionRow> = grid.devices.flatMap { device ->
  val deviceName = deviceNames[device.deviceId].orEmpty()
  device.tasks.flatMap { task ->
    task.cells.map { ConnectionRow(it, task.name, deviceName) }
  }
}

/**
 * A source type in the page's filter.
 *
 * @property source the type, such as `http` or `torrent`.
 * @property label how it reads: its connections' commonest protocol family, such as `HTTP` for
 *   `HTTP/1.1` and `HTTP/2`, or `BitTorrent`.
 * @property count how many connections it has.
 */
internal data class SourceFilter(val source: String, val label: String, val count: Int)

/** The source types of [rows], busiest first. */
internal fun sourceFilters(rows: List<ConnectionRow>): List<SourceFilter> =
  rows.groupBy { it.connection.source }.map { (source, group) ->
    val label = group.groupingBy { it.protocol.substringBefore('/') }.eachCount()
      .maxWith(compareBy<Map.Entry<String, Int>> { it.value }.thenByDescending { it.key }).key
    SourceFilter(source, label, group.size)
  }.sortedWith(compareByDescending<SourceFilter> { it.count }.thenBy { it.source })

/**
 * The rows of [source] (every source with `null`) that match every word of [query] in their
 * endpoint, task, protocol, peer client or device, ignoring case.
 */
internal fun List<ConnectionRow>.matching(source: String?, query: String): List<ConnectionRow> {
  val words = query.trim().lowercase().split(Whitespace).filter { it.isNotEmpty() }
  return filter { row ->
    (source == null || row.connection.source == source) && words.all { word ->
      row.endpoint.lowercase().contains(word) ||
        row.taskName?.lowercase()?.contains(word) == true ||
        row.protocol.lowercase().contains(word) ||
        row.client?.lowercase()?.contains(word) == true ||
        row.deviceName.lowercase().contains(word)
    }
  }
}

/** What the page sorts its connections by. */
internal enum class ConnectionSort(
  /** Whether the first click sorts it largest first, as for speeds and sizes. */
  val numeric: Boolean,
) {
  /** How long it has been open: rows keep their place as speeds change. */
  Opened(numeric = true),
  Endpoint(numeric = false),
  Device(numeric = false),
  Task(numeric = false),
  Protocol(numeric = false),
  Client(numeric = false),
  Route(numeric = false),
  Peer(numeric = false),
  Down(numeric = true),
  Up(numeric = true),
  Received(numeric = true),
  Sent(numeric = true),
}

/**
 * The sort the page shows, ascending unless [descending]. By default the longest open come
 * first, so rows keep their place as speeds change.
 */
internal data class ConnectionArrangement(
  val sort: ConnectionSort = ConnectionSort.Opened,
  val descending: Boolean = true,
) {
  /** This arrangement as `[ui] sort` keeps it, such as `down:desc`. */
  fun encode(): String = "${sort.name.lowercase()}:${if (descending) DESC else ASC}"

  companion object {
    /** The `[ui] sort` key the page keeps its arrangement under. */
    const val KEY = "connections"

    private const val ASC = "asc"
    private const val DESC = "desc"

    /** Decodes [value] made by [encode]; a missing or malformed one gives the default. */
    fun decode(value: String?): ConnectionArrangement {
      val parts = value?.split(':') ?: return ConnectionArrangement()
      if (parts.size != 2) return ConnectionArrangement()
      val sort = ConnectionSort.entries.firstOrNull { it.name.lowercase() == parts[0] }
        ?: return ConnectionArrangement()
      val descending = when (parts[1]) {
        DESC -> true
        ASC -> false
        else -> return ConnectionArrangement()
      }
      return ConnectionArrangement(sort, descending)
    }
  }
}

/**
 * The arrangement after a click on [sort]'s header: its natural direction first (largest first
 * for numbers), then reversed, then back to the default.
 */
internal fun ConnectionArrangement.next(sort: ConnectionSort): ConnectionArrangement = when {
  sort != this.sort -> ConnectionArrangement(sort, descending = sort.numeric)
  descending == sort.numeric -> copy(descending = !descending)
  else -> ConnectionArrangement()
}

/**
 * [this] in [arrangement]'s order, ties the longest open first. How long a connection has been
 * open is its age when its own device sampled it, never its device's clock against another's.
 */
internal fun List<ConnectionRow>.arranged(arrangement: ConnectionArrangement): List<ConnectionRow> {
  val longestOpen = compareByDescending<ConnectionRow> { it.cell.age }
    .thenBy { it.cell.key.deviceId }
    .thenBy { it.cell.key.id }
  val key: Comparator<ConnectionRow> = when (arrangement.sort) {
    // Ascending is the shortest open first, as for the other numbers.
    ConnectionSort.Opened -> compareBy { it.cell.age }
    ConnectionSort.Endpoint -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.endpoint }
    ConnectionSort.Device -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.deviceName }
    ConnectionSort.Task -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.taskName.orEmpty() }
    ConnectionSort.Protocol -> compareBy(String.CASE_INSENSITIVE_ORDER) { it.protocol }
    // Unknown clients last, whichever way.
    ConnectionSort.Client ->
      compareBy<ConnectionRow> { (it.client == null) != arrangement.descending }
        .thenBy(String.CASE_INSENSITIVE_ORDER) { it.client.orEmpty() }
    ConnectionSort.Route -> compareBy({ it.connection.direction }, { it.connection.route })
    ConnectionSort.Peer -> compareBy(
      { it.connection.peer == null },
      { it.connection.peer?.peerChoking },
      { it.connection.peer?.uploadSlot == false },
    )
    ConnectionSort.Down -> compareBy { it.connection.downloadBps }
    ConnectionSort.Up -> compareBy { it.connection.uploadBps }
    ConnectionSort.Received -> compareBy { it.connection.downloadedBytes }
    ConnectionSort.Sent -> compareBy { it.connection.uploadedBytes }
  }
  val ordered = if (arrangement.descending) key.reversed() else key
  return sortedWith(ordered.then(longestOpen))
}

/** Whether a peer connected to this device, rather than this device to it. */
internal val ConnectionRow.incoming: Boolean
  get() = connection.direction == ConnectionDirection.INCOMING

private val Whitespace = Regex("\\s+")
