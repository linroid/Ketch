package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.ActiveConnection
import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.ConnectionDirection
import com.linroid.ketch.api.ConnectionRoute
import com.linroid.ketch.api.PeerDetails
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.ConnectionCellKey
import com.linroid.ketch.app.state.ConnectionGridState
import com.linroid.ketch.app.state.DeviceConnections
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DevicePulse
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.PulseState
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.TaskConnections
import com.linroid.ketch.app.state.connectionCell
import com.linroid.ketch.app.theme.KetchAccent
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.pulse.ConnectionMiniStrip
import com.linroid.ketch.app.ui.pulse.ConnectionStrip
import com.linroid.ketch.app.ui.pulse.ConnectionsContent
import com.linroid.ketch.app.ui.pulse.PulseBarContent
import com.linroid.ketch.app.ui.pulse.SpeedModePillContent
import com.linroid.ketch.app.ui.pulse.SpeedModeView
import com.linroid.ketch.app.ui.pulse.gridDescription
import com.linroid.ketch.config.DensityMode
import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The live connections grid: the Pulse bar's strip at several widths and counts, its popover
 * with mixed traffic, under All devices, on a phone, and in every accent; see [SnapshotHarness]
 * for how to run it.
 */
class ConnectionGridSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun strip_widthsAndCounts_showThePlusAndHideWhenNarrow() {
    val size = SnapshotSize(1312.dp, 760.dp, KetchDensity.Compact)
    SnapshotTheme.entries.forEach { theme ->
      snapshot("pulse-connections-strip", size, theme) {
        Column(
          verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s3),
          modifier = Modifier.padding(KetchTheme.spacing.s4),
        ) {
          for (count in listOf(0, 12, 200)) {
            val grid = ConnectionSamples.grid(ConnectionSamples.many(count), limit = 36)
            for (width in listOf(1280.dp, 760.dp, 600.dp, 520.dp)) {
              StripBar("$count connections, ${width.value.toInt()} dp", grid, width)
            }
          }
        }
      }
    }
  }

  @Test
  fun popover_mixedTraffic_showsGroupsLegendAndDetails() {
    val size = SnapshotSize(420.dp, 760.dp, KetchDensity.Compact)
    val grid = ConnectionSamples.grid(ConnectionSamples.mixed(), limit = 24)
    SnapshotTheme.entries.forEach { theme ->
      snapshot("connections-popover-mixed", size, theme) {
        Box(Modifier.padding(KetchTheme.spacing.s4)) {
          Panel(360.dp) {
            ConnectionsContent(
              grid = grid,
              showDevices = false,
              onShowTask = {},
              initialSelection = ConnectionCellKey(LOCAL_DEVICE_ID, ConnectionSamples.PEER_ID),
            )
          }
        }
      }
    }
  }

  @Test
  fun popover_allDevices_groupsByDeviceAndNotesTheOthers() {
    val size = SnapshotSize(420.dp, 760.dp, KetchDensity.Compact)
    val mac = ConnectionSamples.mixed()
    val nas = ConnectionSamples.nas()
    val grid = ConnectionSamples.grid(
      listOf(LOCAL_DEVICE_ID to mac, ConnectionSamples.NAS_ID to nas),
      unsupported = listOf(verbatim("Office-PC")),
    )
    SnapshotTheme.entries.forEach { theme ->
      snapshot("connections-all-devices", size, theme) {
        Box(Modifier.padding(KetchTheme.spacing.s4)) {
          Panel(360.dp) {
            ConnectionsContent(grid = grid, showDevices = true, onShowTask = {})
          }
        }
      }
    }
  }

  @Test
  fun phoneSheet_tappedCell_showsItsDetails() {
    val grid = ConnectionSamples.grid(ConnectionSamples.mixed(), limit = 512)
    val strip = ConnectionSamples.grid(ConnectionSamples.mixed(), limit = 36)
    SnapshotTheme.entries.forEach { theme ->
      snapshot(
        name = "connections-phone-sheet",
        size = SnapshotSize.Phone,
        theme = theme,
        interact = {
          // The sixth peer of the torrent, which downloads and uploads over IPv6.
          click(x = 97.dp, y = 261.dp)
        },
      ) {
        Column(Modifier.fillMaxSize().background(KetchTheme.colors.surface)) {
          val spacing = KetchTheme.spacing
          Column(Modifier.padding(horizontal = spacing.s4, vertical = spacing.s3)) {
            Text(
              text = "Downloads",
              style = KetchTheme.typography.pageTitle,
              color = KetchTheme.colors.textPrimary,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
              Text(
                text = "↓ 27.9 MB/s · 3 active · Full",
                style = KetchTheme.typography.caption,
                color = KetchTheme.colors.textSecondary,
              )
              ConnectionMiniStrip(strip, Modifier.padding(start = spacing.s2))
            }
          }
          Column(
            Modifier
              .fillMaxWidth()
              .weight(1f)
              .ketchSurface(
                KetchElevationLevel.E4,
                KetchTheme.shapes.sheetTop,
                KetchTheme.colors.surfaceRaised,
              )
              .padding(KetchTheme.density.pagePadding),
          ) {
            ConnectionsContent(grid = grid, showDevices = false, onShowTask = {})
          }
        }
      }
    }
  }

  @Test
  fun strip_everyAccent_keepsUploadApartFromDownload() {
    val size = SnapshotSize(680.dp, 640.dp, KetchDensity.Compact)
    val grid = ConnectionSamples.grid(ConnectionSamples.many(36, seed = 3), limit = 36)
    SnapshotTheme.entries.forEach { theme ->
      snapshot("connections-accents", size, theme) {
        Column(
          verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s3),
          modifier = Modifier.padding(KetchTheme.spacing.s4),
        ) {
          for (accent in KetchAccent.entries) {
            KetchTheme(
              darkTheme = theme == SnapshotTheme.Dark,
              accent = accent,
              density = DensityMode.Compact,
              reduceMotion = true,
            ) {
              StripBar(accent.name, grid, 640.dp)
            }
          }
        }
      }
    }
  }

  @Test
  fun app_desktopAndPhone_showTheGridOnDownloads() {
    val data = {
      val sample = SampleData.downloads()
      SampleData(sample.tasks, connections = ConnectionSamples.mixed())
    }
    for (theme in SnapshotTheme.entries) {
      appSnapshot("connections-app", SnapshotSize.Desktop, theme, data()) {
        val strip = ConnectionSamples.grid(ConnectionSamples.mixed(), limit = 36)
        scene.clickOn(strip.gridDescription().load())
        scene.settle()
      }
      appSnapshot("connections-app", SnapshotSize.Phone, theme, data())
    }
  }
}

/** A Pulse bar [width] wide with [grid] in its strip, under [label]. */
@Composable
private fun StripBar(label: String, grid: ConnectionGridState, width: Dp) {
  Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1)) {
    Text(label, style = KetchTheme.typography.caption, color = KetchTheme.colors.textSecondary)
    Box(
      Modifier
        .width(width)
        .clip(KetchTheme.shapes.sm)
        .background(KetchTheme.colors.surface),
    ) {
      PulseBarContent(
        pulse = BusyPulse,
        unread = 2,
        selection = null,
        onShowTab = {},
        onSpeedClick = {},
        onHealthClick = {},
        onActivityClick = {},
        pill = { SpeedModePillContent(FullSpeed, onToggle = {}, onOptions = {}) },
        connections = { ConnectionStrip(grid, onClick = {}) },
      )
    }
  }
}

@Composable
private fun Panel(width: Dp, content: @Composable ColumnScope.() -> Unit) {
  Column(
    modifier = Modifier
      .width(width)
      .ketchSurface(
        KetchElevationLevel.E3,
        KetchTheme.shapes.menu,
        KetchTheme.colors.surfaceRaised,
        KetchTheme.colors.hairline,
      )
      .padding(KetchTheme.spacing.s4),
    content = content,
  )
}

private val BusyPulse = PulseState(
  listOf(
    DevicePulse(
      deviceId = LOCAL_DEVICE_ID,
      name = verbatim("This Mac"),
      health = DeviceHealth.Local(sharingPort = 8642),
      counts = PulseCounts(downloading = 3, waiting = 2, done = 4, failed = 1),
      failures = 1,
      speed = 27_997_000,
      cap = SpeedLimit.Unlimited,
      downloadedBytes = 0,
      sizeBytes = 0,
      sizesKnown = true,
      pendingBytes = 0,
      disk = null,
      history = List(60) { (24_000_000 * (0.8 + 0.2 * sin(it * PI / 9))).roundToLong() },
    ),
  ),
)

private val FullSpeed = SpeedModeView(
  mode = SpeedMode.Full,
  limit = SpeedLimit.Unlimited,
  label = verbatim("Full speed"),
  controller = null,
)

/** Connections of the sample downloads, and grids built from them as the model builds them. */
internal object ConnectionSamples {
  const val NAS_ID = "nas.local:8642"

  /** The torrent peer [mixed] selects: incoming over IPv6, downloading and uploading. */
  const val PEER_ID = 21L

  private val NAMES = mapOf(
    "ubuntu" to "ubuntu-24.04-desktop-amd64.iso",
    "imagenet-03" to "imagenet-part03.tar",
    "archlinux" to "archlinux-2026.10.01-x86_64.iso",
    "debian-cd" to "debian-13.1.0-amd64-DVD-1.iso",
    "fedora" to "Fedora-Workstation-Live-43.iso",
  )

  /**
   * Eight HTTP/2 segments of ubuntu, one idle; four HTTP/1.1 segments of imagenet through a
   * proxy; an FTP transfer of a task the list does not show; and twelve torrent peers in and out,
   * uploading, both and idle.
   */
  fun mixed(): ActiveConnections {
    val list = buildList {
      val ubuntu =
        listOf(2_600_000L, 2_300_000, 2_250_000, 1_900_000, 1_700_000, 900_000, 600_000, 0)
      ubuntu.forEachIndexed { i, rate ->
        add(
          connection(
            id = 1L + i,
            taskId = "ubuntu",
            host = "releases.ubuntu.com",
            port = 443,
            protocol = "HTTP/2",
            secure = true,
            down = rate,
            age = 420 - i,
          ),
        )
      }
      listOf(1_800_000L, 2_100_000, 230_000, 12_000).forEachIndexed { i, rate ->
        add(
          connection(
            id = 11L + i,
            taskId = "imagenet-03",
            host = "image-net.org",
            port = 443,
            protocol = "HTTP/1.1",
            secure = true,
            down = rate,
            age = 2_500 - i,
            route = ConnectionRoute.HTTP_PROXY,
          ),
        )
      }
      add(
        connection(
          id = 15,
          taskId = "debian-cd",
          source = "ftp",
          host = "ftp.debian.org",
          port = 21,
          protocol = "FTP",
          secure = false,
          down = 640_000,
          age = 95,
        ),
      )
      val peers = listOf(
        Triple("203.0.113.24", 51413, 880_000L to 0L),
        Triple("198.51.100.7", 6881, 410_000L to 64_000L),
        Triple("192.0.2.140", 49160, 0L to 120_000L),
        Triple("203.0.113.88", 6881, 96_000L to 0L),
        Triple("198.51.100.201", 51820, 0L to 0L),
        Triple("2001:db8:4c::17", 6881, 540_000L to 210_000L),
        Triple("192.0.2.33", 42001, 12_000L to 0L),
        Triple("203.0.113.5", 6889, 0L to 3_000L),
        Triple("198.51.100.66", 6881, 1_200_000L to 0L),
        Triple("192.0.2.91", 51413, 0L to 0L),
        Triple("203.0.113.150", 6881, 260_000L to 30_000L),
        Triple("198.51.100.12", 55000, 2_400L to 0L),
      )
      peers.forEachIndexed { i, (host, port, rates) ->
        val incoming = i % 3 == 2 || i == 5
        add(
          connection(
            id = 16L + i,
            taskId = "archlinux",
            source = "torrent",
            host = host,
            port = port,
            protocol = "BitTorrent",
            secure = false,
            down = rates.first,
            up = rates.second,
            age = 700 - 40 * i,
            direction = if (incoming) INCOMING else ConnectionDirection.OUTGOING,
            route = ConnectionRoute.DIRECT,
            peer = PeerDetails(
              wire = if (i % 4 == 1) "v1" else "v2",
              peerChoking = rates.first == 0L,
              uploadSlot = rates.second > 0,
              peerInterested = rates.second > 0,
            ),
            uploaded = rates.second * 300,
          ),
        )
      }
    }
    return snapshot(list, total = list.size + 37)
  }

  /** A NAS seeding one torrent and downloading over HTTP. */
  fun nas(): ActiveConnections {
    val list = buildList {
      repeat(6) { i ->
        add(
          connection(
            id = 1L + i,
            taskId = "fedora",
            source = "torrent",
            host = "198.51.100.${30 + i}",
            port = 6881 + i,
            protocol = "BitTorrent",
            secure = false,
            up = if (i % 3 == 0) 0 else 150_000L * (i + 1),
            age = 900 - 60 * i,
            direction = ConnectionDirection.INCOMING,
            peer = PeerDetails(
              wire = "v1",
              peerChoking = true,
              uploadSlot = i % 3 != 0,
              peerInterested = true,
            ),
          ),
        )
      }
      repeat(4) { i ->
        add(
          connection(
            id = 10L + i,
            taskId = "debian-cd",
            host = "cdimage.debian.org",
            port = 443,
            protocol = "HTTP/1.1",
            secure = true,
            down = 1_400_000L - 300_000L * i,
            age = 200 - i,
          ),
        )
      }
    }
    return snapshot(list)
  }

  /** [count] connections of a busy instance, mostly torrent peers, from [seed]. */
  fun many(count: Int, seed: Int = 11): ActiveConnections {
    val random = Random(seed)
    val list = List(count) { i ->
      val kind = random.nextDouble()
      val rate = { (1024 * 10.0.pow(random.nextDouble() * 3.7)).roundToLong() }
      val (down, up) = when {
        kind < 0.15 -> 0L to 0L
        kind < 0.65 -> rate() to 0L
        kind < 0.82 -> 0L to rate()
        else -> rate() to rate()
      }
      val torrent = i % 5 != 0
      connection(
        id = 1L + i,
        taskId = if (torrent) "archlinux" else "ubuntu",
        source = if (torrent) "torrent" else "http",
        host = if (torrent) "203.0.113.${i % 250}" else "releases.ubuntu.com",
        port = if (torrent) 6881 + i % 40 else 443,
        protocol = if (torrent) "BitTorrent" else "HTTP/2",
        secure = !torrent,
        down = down,
        up = up,
        age = 2_000 - i,
      )
    }
    return snapshot(list)
  }

  /** The grid of one device's [snapshot] cut to [limit], as the model builds it. */
  fun grid(snapshot: ActiveConnections, limit: Int): ConnectionGridState {
    val kept = snapshot.connections
      .sortedByDescending { it.downloadBps + it.uploadBps }
      .take(limit)
      .sortedBy { it.openedAt }
    return grid(listOf(LOCAL_DEVICE_ID to snapshot.copy(connections = kept)))
  }

  /** The grid of each device's snapshot, in order. */
  fun grid(
    devices: List<Pair<String, ActiveConnections>>,
    unsupported: List<UiText> = emptyList(),
  ): ConnectionGridState {
    val order = NAMES.keys.toList()
    return ConnectionGridState(
      devices = devices.map { (deviceId, snapshot) ->
        DeviceConnections(
          deviceId = deviceId,
          name = verbatim(if (deviceId == LOCAL_DEVICE_ID) "MacBook Pro" else "NAS-Basement"),
          tasks = snapshot.connections
            .map { connectionCell(deviceId, it, snapshot) }
            .groupBy { it.task }
            .map { (key, cells) ->
              // The FTP transfer's task is not in the list, so it has no name.
              val hidden = key.taskId == "debian-cd" && deviceId == LOCAL_DEVICE_ID
              val name = NAMES[key.taskId].takeUnless { hidden }
              TaskConnections(key, name, cells.sortedBy { it.connection.openedAt })
            }
            .sortedBy { order.indexOf(it.key.taskId) },
        )
      },
      total = devices.sumOf { it.second.total },
      downloadBps = devices.sumOf { it.second.downloadBps },
      uploadBps = devices.sumOf { it.second.uploadBps },
      supported = true,
      unsupported = unsupported,
    )
  }

  private val INCOMING = ConnectionDirection.INCOMING

  private fun snapshot(list: List<ActiveConnection>, total: Int = list.size) = ActiveConnections(
    sampledAt = SampleData.NOW,
    connections = list.sortedBy { it.openedAt },
    total = total,
    downloadBps = list.sumOf { it.downloadBps },
    uploadBps = list.sumOf { it.uploadBps },
  )

  private fun connection(
    id: Long,
    taskId: String,
    host: String,
    port: Int,
    protocol: String,
    secure: Boolean,
    age: Int,
    source: String = "http",
    down: Long = 0,
    up: Long = 0,
    direction: ConnectionDirection = ConnectionDirection.OUTGOING,
    route: ConnectionRoute = ConnectionRoute.DIRECT,
    peer: PeerDetails? = null,
    uploaded: Long = up * 60,
  ) = ActiveConnection(
    id = id,
    taskId = taskId,
    source = source,
    protocol = protocol,
    secure = secure,
    direction = direction,
    host = host,
    port = port,
    route = route,
    downloadBps = down,
    uploadBps = up,
    downloadedBytes = down * age,
    uploadedBytes = uploaded,
    openedAt = SampleData.NOW - age.seconds,
    peer = peer,
  )
}
