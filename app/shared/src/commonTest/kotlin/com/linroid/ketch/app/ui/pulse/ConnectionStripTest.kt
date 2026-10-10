package com.linroid.ketch.app.ui.pulse

import com.linroid.ketch.api.ActiveConnection
import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.api.ConnectionRoute
import com.linroid.ketch.api.PeerDetails
import com.linroid.ketch.app.components.CellPaint
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.ConnectionCell
import com.linroid.ketch.app.state.ConnectionGridState
import com.linroid.ketch.app.state.DeviceConnections
import com.linroid.ketch.app.state.TaskConnections
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TrafficDirection
import com.linroid.ketch.app.state.connectionCell
import com.linroid.ketch.app.theme.lightKetchColors
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class ConnectionStripTest {
  private val colors = lightKetchColors()

  @Test
  fun endpointText_hostsAndPorts_bracketIpv6() {
    assertEquals("example.org:443", endpointText("example.org", 443))
    assertEquals("[2001:db8::1]:6881", endpointText("2001:db8::1", 6881))
    assertEquals("203.0.113.9", endpointText("203.0.113.9", null))
  }

  @Test
  fun paint_eachDirection_hasItsOwnShape() {
    val traffic = colors.traffic
    assertEquals(CellPaint(fill = traffic.download[3]), cell(down = 3 * MIB).paint(colors))
    val upload = cell(up = 20 * KIB).paint(colors)
    assertEquals(traffic.uploadSoft, upload.fill)
    assertEquals(traffic.upload[1], upload.outline)
    assertNull(upload.split)
    val both = cell(down = 100, up = 300 * KIB).paint(colors)
    assertEquals(traffic.download[2], both.fill)
    assertEquals(traffic.upload[2], both.split)
    val idle = cell().paint(colors)
    assertEquals(colors.surfaceSunken, idle.fill)
    assertEquals(colors.hairline, idle.outline)
  }

  @Test
  fun tooltipText_httpConnection_namesEndpointProtocolAndRates() = runTest {
    val text = cell(down = 1_258_291, protocol = "HTTP/2").tooltipText().load()

    assertEquals("example.org:443 · HTTP/2 · ↓ 1.2 MB/s · ↑ 0 B/s", text)
  }

  @Test
  fun description_cell_readsTaskEndpointStateAndSpeeds() = runTest {
    val text = cell(down = 2048).description("ubuntu.iso").load()

    assertEquals("ubuntu.iso, example.org:443, Downloading, down 2.0 KB/s, up 0 B/s", text)
  }

  @Test
  fun description_unknownTask_fallsBackToItsId() = runTest {
    assertEquals(
      "t1, example.org:443, Idle, down 0 B/s, up 0 B/s",
      cell().description(null).load(),
    )
  }

  @Test
  fun gridDescription_cells_countsDownloadsAndUploads() = runTest {
    val cells = listOf(cell(down = 1), cell(up = 1), cell(down = 1, up = 1), cell())

    assertEquals("4 connections, 2 downloading, 2 uploading", gridDescription(cells).load())
  }

  @Test
  fun gridDescription_overflow_countsTheCellsThenTheRest() = runTest {
    val grid = ConnectionGridState(
      devices = listOf(
        DeviceConnections(
          deviceId = "mac",
          name = verbatim("mac"),
          tasks = listOf(
            TaskConnections(TaskKey("mac", "t1"), "a.iso", listOf(cell(down = 1), cell(down = 1))),
          ),
        ),
      ),
      total = 100,
    )

    assertEquals(
      "2 connections, 2 downloading, 0 uploading · 98 more",
      grid.gridDescription().load(),
    )
  }

  @Test
  fun summaryText_grid_countsEveryConnectionWithItsRates() = runTest {
    val grid = ConnectionGridState(
      devices = listOf(
        DeviceConnections(
          deviceId = "mac",
          name = verbatim("mac"),
          tasks = listOf(TaskConnections(TaskKey("mac", "t1"), "a.iso", listOf(cell(down = 1)))),
        ),
      ),
      total = 42,
      downloadBps = 3_355_443,
      uploadBps = 122_880,
    )

    assertEquals("42 connections · ↓ 3.2 MB/s · ↑ 120.0 KB/s", grid.summaryText().load())
    assertEquals(41, grid.overflow)
  }

  @Test
  fun details_proxiedTlsConnection_listsTransportAndRoute() = runTest {
    val details = cell(
      down = 1024,
      protocol = "HTTP/1.1",
      secure = true,
      route = ConnectionRoute.SOCKS5_PROXY,
      downloaded = 5 * MIB,
    ).details()

    assertEquals("example.org:443", details.endpoint)
    assertEquals("HTTP/1.1 · Encrypted", details.transport.load())
    assertEquals("Outgoing · Via SOCKS5 proxy", details.route.load())
    assertNull(details.peer)
    assertEquals("Received 5.0 MB · Sent 0 B · Open for 1m 30s", details.totals.load())
  }

  @Test
  fun details_torrentPeer_listsWireAndChoking() = runTest {
    val details = cell(
      up = 10,
      direction = ConnectionDirection.INCOMING,
      route = ConnectionRoute.UNKNOWN,
      peer = PeerDetails(wire = "v2", peerChoking = true, uploadSlot = true, peerInterested = true),
      source = "torrent",
    ).details()

    assertEquals("TORRENT", details.transport.load())
    assertEquals("Incoming", details.route.load())
    val peer = assertNotNull(details.peer).load()
    assertEquals("v2 · Peer is choking · Uploading to this peer", peer)
  }

  @Test
  fun trafficLabel_eachDirection_isNamed() = runTest {
    assertEquals(
      listOf("Downloading", "Uploading", "Both", "Idle"),
      TrafficDirection.entries.map { trafficLabel(it).load() },
    )
  }

  private fun cell(
    down: Long = 0,
    up: Long = 0,
    protocol: String? = null,
    secure: Boolean? = null,
    direction: ConnectionDirection = ConnectionDirection.OUTGOING,
    route: ConnectionRoute = ConnectionRoute.DIRECT,
    peer: PeerDetails? = null,
    source: String = "http",
    downloaded: Long = 0,
  ): ConnectionCell {
    val connection = ActiveConnection(
      id = 1,
      taskId = "t1",
      source = source,
      protocol = protocol,
      secure = secure,
      direction = direction,
      host = "example.org",
      port = 443,
      route = route,
      downloadBps = down,
      uploadBps = up,
      downloadedBytes = downloaded,
      openedAt = NOW - 90.seconds,
      peer = peer,
    )
    return connectionCell("mac", connection, ActiveConnections(NOW, listOf(connection), 1))
  }

  private companion object {
    val NOW: Instant = Instant.parse("2026-10-01T14:30:00Z")
    const val KIB = 1024L
    const val MIB = 1024L * 1024
  }
}
