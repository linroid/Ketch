package com.linroid.ketch.app.ui.connections

import com.linroid.ketch.api.ActiveConnection
import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.PeerDetails
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.ConnectionGridState
import com.linroid.ketch.app.state.DeviceConnections
import com.linroid.ketch.app.state.TaskConnections
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.connectionCell
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ConnectionRowsTest {
  private val http = connection(1, "ubuntu", "HTTP/2", down = 500, opened = 30)
  private val proxied = connection(2, "ubuntu", "HTTP/1.1", down = 900, opened = 20)
  private val peer = connection(3, "sintel", "BitTorrent", source = "torrent", up = 70,
    opened = 10, host = "203.0.113.9")
  private val seed = connection(4, "sintel", "HTTP", source = "torrent", down = 10, opened = 5,
    host = "webtorrent.io")

  private val rows = connectionRows(
    grid(
      "mac" to listOf("ubuntu.iso" to listOf(http, proxied)),
      "nas" to listOf("Sintel" to listOf(peer, seed)),
    ),
    mapOf("mac" to "This Mac", "nas" to "NAS"),
  )

  @Test
  fun connectionRows_nameEachTaskAndDevice() {
    assertEquals(listOf("ubuntu.iso", "ubuntu.iso", "Sintel", "Sintel"), rows.map { it.taskName })
    assertEquals(listOf("This Mac", "This Mac", "NAS", "NAS"), rows.map { it.deviceName })
  }

  @Test
  fun sourceFilters_nameEachSourceByItsCommonestProtocol_busiestFirst() {
    val torrents = listOf(peer, seed, connection(5, "sintel", "BitTorrent", source = "torrent"))
    val filters = sourceFilters(
      connectionRows(grid("mac" to listOf("x" to torrents + http)), mapOf("mac" to "Mac")),
    )
    assertEquals(
      listOf(SourceFilter("torrent", "BitTorrent", 3), SourceFilter("http", "HTTP", 1)),
      filters,
    )
  }

  @Test
  fun matching_needsEveryWordInSomeField() {
    assertEquals(listOf(3L, 4L), rows.matching("torrent", "").map { it.connection.id })
    assertEquals(listOf(1L, 2L), rows.matching(null, "UBUNTU http").map { it.connection.id })
    assertEquals(listOf(3L), rows.matching(null, "203.0.113 nas").map { it.connection.id })
    assertEquals(emptyList(), rows.matching("http", "sintel"))
    // A torrent peer's client counts as a field.
    assertEquals(listOf(3L), rows.matching(null, "qbittorrent").map { it.connection.id })
  }

  @Test
  fun arranged_byClient_keepsUnknownClientsLast() {
    val byClient = ConnectionArrangement().next(ConnectionSort.Client)
    assertEquals(3L, rows.arranged(byClient).first().connection.id)
    assertEquals(3L, rows.arranged(byClient.next(ConnectionSort.Client)).first().connection.id)
  }

  @Test
  fun arranged_defaultsToTheLongestOpen_andSortsNumbersLargestFirst() {
    assertEquals(listOf(1L, 2L, 3L, 4L), rows.arranged(ConnectionArrangement()).ids())
    val newest = ConnectionArrangement().next(ConnectionSort.Opened)
    assertEquals(listOf(4L, 3L, 2L, 1L), rows.arranged(newest).ids())
    val down = ConnectionArrangement().next(ConnectionSort.Down)
    assertEquals(ConnectionArrangement(ConnectionSort.Down, descending = true), down)
    assertEquals(listOf(2L, 1L, 4L, 3L), rows.arranged(down).ids())
    val task = ConnectionArrangement().next(ConnectionSort.Task)
    assertEquals(listOf(3L, 4L, 1L, 2L), rows.arranged(task).ids())
  }

  @Test
  fun arrangement_roundTrips_andFallsBackToTheDefault() {
    for (sort in ConnectionSort.entries) {
      for (descending in listOf(true, false)) {
        val arrangement = ConnectionArrangement(sort, descending)
        assertEquals(arrangement, ConnectionArrangement.decode(arrangement.encode()))
      }
    }
    assertEquals("down:desc", ConnectionArrangement(ConnectionSort.Down).encode())
    for (value in listOf(null, "", "down", "speed:desc", "down:sideways", "down:desc:x")) {
      assertEquals(ConnectionArrangement(), ConnectionArrangement.decode(value))
    }
  }

  @Test
  fun arranged_longestOpen_readsEachDevicesOwnClock() {
    // The NAS's clock runs an hour behind: its connection opened 5 s before its sample, which
    // reads as an hour earlier than the Mac's, open for a minute.
    val mac = connection(1, "a", "HTTP/2", opened = 60)
    val nasClock = NOW - 3600.seconds
    val nas = connection(2, "b", "HTTP/2").copy(openedAt = nasClock - 5.seconds)
    val grid = ConnectionGridState(
      devices = listOf(
        device("mac", mac, ActiveConnections(sampledAt = NOW, connections = listOf(mac))),
        device("nas", nas, ActiveConnections(sampledAt = nasClock, connections = listOf(nas))),
      ),
      total = 2,
      supported = true,
    )
    val rows = connectionRows(grid, mapOf("mac" to "Mac", "nas" to "NAS"))
    assertEquals(listOf(1L, 2L), rows.arranged(ConnectionArrangement()).ids())
  }

  private fun device(deviceId: String, connection: ActiveConnection, snapshot: ActiveConnections) =
    DeviceConnections(
      deviceId = deviceId,
      name = verbatim(deviceId),
      tasks = listOf(
        TaskConnections(
          key = TaskKey(deviceId, connection.taskId),
          name = connection.taskId,
          cells = listOf(connectionCell(deviceId, connection, snapshot)),
        ),
      ),
    )

  @Test
  fun next_cyclesNaturalReversedThenTheDefault() {
    val first = ConnectionArrangement().next(ConnectionSort.Endpoint)
    assertEquals(ConnectionArrangement(ConnectionSort.Endpoint, descending = false), first)
    val second = first.next(ConnectionSort.Endpoint)
    assertEquals(ConnectionArrangement(ConnectionSort.Endpoint, descending = true), second)
    assertEquals(ConnectionArrangement(), second.next(ConnectionSort.Endpoint))
  }

  @Test
  fun fitColumns_dropsTheLeastTellingFirst_andKeepsSpeeds() {
    val wide = fitColumns(available = 2000.dp, devices = true, peers = true)
    assertEquals(ConnectionColumn.entries.toList(), wide)
    val noPeers = fitColumns(available = 2000.dp, devices = false, peers = false)
    assertEquals(false, ConnectionColumn.Peer in noPeers || ConnectionColumn.Device in noPeers)
    val narrow = fitColumns(available = 420.dp, devices = true, peers = true)
    assertEquals(listOf(ConnectionColumn.Down, ConnectionColumn.Up), narrow)
  }

  private fun List<ConnectionRow>.ids() = map { it.connection.id }

  private fun grid(
    vararg devices: Pair<String, List<Pair<String, List<ActiveConnection>>>>,
  ): ConnectionGridState {
    val all = devices.flatMap { (_, tasks) -> tasks.flatMap { it.second } }
    val snapshot = ActiveConnections(sampledAt = NOW, connections = all, total = all.size)
    return ConnectionGridState(
      devices = devices.map { (deviceId, tasks) ->
        DeviceConnections(
          deviceId = deviceId,
          name = verbatim(deviceId),
          tasks = tasks.map { (name, connections) ->
            TaskConnections(
              key = TaskKey(deviceId, connections.first().taskId),
              name = name,
              cells = connections.map { connectionCell(deviceId, it, snapshot) },
            )
          },
        )
      },
      total = all.size,
      supported = true,
    )
  }

  private fun connection(
    id: Long,
    taskId: String,
    protocol: String,
    source: String = "http",
    down: Long = 0,
    up: Long = 0,
    opened: Long = 0,
    host: String = "releases.ubuntu.com",
  ) = ActiveConnection(
    id = id,
    taskId = taskId,
    source = source,
    protocol = protocol,
    host = host,
    port = if (source == "torrent") 6881 else 443,
    downloadBps = down,
    uploadBps = up,
    openedAt = NOW - opened.seconds,
    peer = if (protocol == "BitTorrent") {
      PeerDetails("v1", true, false, false, client = "qBittorrent 4.6.3")
    } else {
      null
    },
  )

  private companion object {
    val NOW = Instant.fromEpochSeconds(1_800_000_000)
  }
}
