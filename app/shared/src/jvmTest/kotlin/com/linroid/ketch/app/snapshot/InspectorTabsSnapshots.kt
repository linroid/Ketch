package com.linroid.ketch.app.snapshot

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.SpeedHistory
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.TimelineEntry
import com.linroid.ketch.app.state.TimelineKind
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.inspector.tabs.ActivityTab
import com.linroid.ketch.app.ui.inspector.tabs.ActivityTabContent
import com.linroid.ketch.app.ui.inspector.tabs.ConnectionsTab
import com.linroid.ketch.app.ui.inspector.tabs.ConnectionsTabContent
import com.linroid.ketch.app.ui.inspector.tabs.FilesTab
import com.linroid.ketch.app.util.SegmentRate
import com.linroid.ketch.config.DensityMode
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.TimeZone
import kotlin.math.PI
import kotlin.math.roundToLong
import kotlin.math.sin
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The inspector's Connections, Files and Activity tabs in the docked width, its widest and on a
 * phone, in light and dark; see [SnapshotHarness] for how to run it.
 */
class InspectorTabsSnapshots {
  @BeforeTest
  fun enabled() = requireSnapshots()

  @Test
  fun tabs_dockedWidth_rendersEachTab() {
    for (theme in SnapshotTheme.entries) {
      snapshot("inspector-tabs", Wide, theme) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s4),
          modifier = Modifier.padding(KetchTheme.spacing.s4),
        ) {
          TabCard("Connections 6", DockedWidth) {
            ConnectionsTabContent(ubuntu(), RATES, onConnectionsChange = {})
          }
          TabCard("Files 14", DockedWidth) { FilesTab(season()) }
          TabCard("Activity", DockedWidth) { Activity(ubuntu()) }
        }
      }
    }
  }

  @Test
  fun connections_hoverAndKeyboard_highlightTheLaneAndFocus() {
    val size = SnapshotSize(360.dp, 420.dp, KetchDensity.Compact)
    for (theme in SnapshotTheme.entries) {
      snapshot(
        name = "inspector-connections-hover",
        size = size,
        theme = theme,
        interact = {
          hover(120.dp, 190.dp)
          pressKey(Key.Tab)
        },
      ) {
        Column(Modifier.padding(KetchTheme.spacing.s4)) {
          TabCard("Connections 6", DockedWidth) {
            ConnectionsTabContent(ubuntu(), RATES, onConnectionsChange = {})
          }
        }
      }
    }
  }

  @Test
  fun activity_hover_showsTheCrosshair() {
    val size = SnapshotSize(360.dp, 560.dp, KetchDensity.Compact)
    for (theme in SnapshotTheme.entries) {
      snapshot("inspector-activity-hover", size, theme, interact = { hover(200.dp, 120.dp) }) {
        Column(Modifier.padding(KetchTheme.spacing.s4)) {
          TabCard("Activity", DockedWidth) { Activity(ubuntu(), SpeedLimit.mbps(12)) }
        }
      }
    }
  }

  @Test
  fun connections_eachScaleAndState_rendersLanes() {
    for (theme in SnapshotTheme.entries) {
      snapshot("inspector-connections", Wide, theme) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s4),
          modifier = Modifier.padding(KetchTheme.spacing.s4),
        ) {
          Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s4)) {
            TabCard("Paused", DockedWidth) {
              ConnectionsTabContent(studio(), emptyList(), onConnectionsChange = {})
            }
            TabCard("Single connection", DockedWidth) {
              val rates = listOf(SegmentRate(0, 2_400_000))
              ConnectionsTabContent(single(), rates, onConnectionsChange = {})
            }
          }
          val sixteen = lanes(16, done = 0.3)
          TabCard("Connections 16", DockedWidth) {
            ConnectionsTabContent(sixteen, rates(sixteen.segments), onConnectionsChange = {})
          }
          val dense = lanes(24, done = 0.45)
          TabCard("Connections 24", DockedWidth) {
            ConnectionsTabContent(dense, rates(dense.segments), onConnectionsChange = {})
          }
        }
      }
    }
  }

  @Test
  fun tabs_widestDock_fillTheWidth() {
    for (theme in SnapshotTheme.entries) {
      snapshot("inspector-tabs-480", SnapshotSize(1060.dp, 640.dp, KetchDensity.Compact), theme) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s4),
          modifier = Modifier.padding(KetchTheme.spacing.s4),
        ) {
          TabCard("Connections 6", WidestDock) {
            ConnectionsTabContent(ubuntu(), RATES, onConnectionsChange = {})
          }
          TabCard("Files 300", WidestDock) { FilesTab(pack()) }
        }
      }
    }
  }

  @Test
  fun tabs_phone_rendersEachTab() {
    for (theme in SnapshotTheme.entries) {
      snapshot("inspector-tabs-phone-connections", SnapshotSize.Phone, theme) {
        PhoneSheet { ConnectionsTabContent(ubuntu(), RATES, onConnectionsChange = {}) }
      }
      snapshot("inspector-tabs-phone-files", SnapshotSize.Phone, theme) {
        PhoneSheet { FilesTab(pack()) }
      }
      snapshot("inspector-tabs-phone-activity", SnapshotSize.Phone, theme) {
        PhoneSheet { Activity(ubuntu(), globalLimit = SpeedLimit.mbps(12)) }
      }
    }
  }

  @Test
  fun tabs_overApp_followTheStores() {
    val size = SnapshotSize(720.dp, 560.dp, KetchDensity.Compact)
    for (theme in SnapshotTheme.entries) {
      val data = SampleData.downloads()
      val environment = runBlocking(SnapshotHarness.ui) {
        SampleEnvironment(data, theme, DensityMode.Compact)
      }
      try {
        runBlocking(SnapshotHarness.ui) { withTimeout(5.seconds) { environment.start() } }
        val state = environment.controller.state
        snapshot("inspector-tabs-live", size, theme) {
          val rows by state.taskList.rows.collectAsState()
          val row = rows.firstOrNull { it.name == "ubuntu-24.04-desktop-amd64.iso" }
          if (row != null) {
            Row(
              horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s4),
              modifier = Modifier.padding(KetchTheme.spacing.s4),
            ) {
              TabCard("Connections", DockedWidth) { ConnectionsTab(state, row) }
              TabCard("Activity", DockedWidth) { ActivityTab(state, row) }
            }
          }
        }
      } finally {
        runBlocking(SnapshotHarness.ui) { environment.close() }
      }
    }
  }

  private companion object {
    val Wide = SnapshotSize(1104.dp, 780.dp, KetchDensity.Compact)
    val DockedWidth = 320.dp
    val WidestDock = 480.dp

    /** Rates of [ubuntu]'s lanes: one stalled for 6 s and one stuck for 12 s. */
    val RATES = listOf(
      SegmentRate(start(1, 8, UBUNTU), 1_380_000),
      SegmentRate(start(2, 8, UBUNTU), 1_150_000),
      SegmentRate(start(3, 8, UBUNTU), 0, stalledFor = 6.seconds),
      SegmentRate(start(4, 8, UBUNTU), 1_240_000),
      SegmentRate(start(5, 8, UBUNTU), 980_000),
      SegmentRate(start(6, 8, UBUNTU), 0, stalledFor = 12.seconds)
    )

    const val UBUNTU = 6_114_656_256L

    fun start(index: Int, count: Int, total: Long): Long = index * (total / count)
  }
}

/** One tab in a card the width of the inspector, under a label naming the case. */
@Composable
private fun TabCard(label: String, width: Dp, content: @Composable () -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .width(width)
      .ketchSurface(KetchElevationLevel.E1, KetchTheme.shapes.lg, colors.surface, colors.hairline)
      .padding(spacing.s3),
  ) {
    KetchEyebrow(label)
    content()
  }
}

/** A tab filling a phone's bottom sheet. */
@Composable
private fun PhoneSheet(content: @Composable () -> Unit) {
  val colors = KetchTheme.colors
  Column(
    horizontalAlignment = Alignment.Start,
    modifier = Modifier
      .fillMaxWidth()
      .padding(top = KetchTheme.spacing.s16)
      .ketchSurface(KetchElevationLevel.E0, KetchTheme.shapes.sheetTop, colors.surface)
      .padding(KetchTheme.spacing.s4),
  ) {
    content()
  }
}

@Composable
private fun Activity(row: TaskRow, globalLimit: SpeedLimit = SpeedLimit.Unlimited) {
  ActivityTabContent(
    row = row,
    history = history(row),
    timeline = TIMELINE,
    globalLimit = globalLimit,
    timeZone = TimeZone.UTC,
  )
}

/** Eight connections of the Ubuntu image: two finished, six downloading. */
private fun ubuntu(): TaskRow {
  val total = 6_114_656_256L
  val done = listOf(1.0, 0.94, 0.86, 0.79, 0.71, 0.64, 0.52, 1.0)
  val segments = split(total, done)
  val request = DownloadRequest(
    url = "https://releases.ubuntu.com/24.04/ubuntu-24.04-desktop-amd64.iso",
    connections = 8,
    speedLimit = SpeedLimit.mbps(8),
  )
  val progress = DownloadProgress(segments.sumOf { it.downloadedBytes }, total, 6_710_886)
  return ListFixtures.row(
    id = "ubuntu",
    state = DownloadState.Downloading(progress),
    request = request,
    now = SampleData.NOW,
  ).copy(segments = segments)
}

/** The Android Studio image paused at 37% over four connections. */
private fun studio(): TaskRow {
  val total = 1_342_177_280L
  val segments = split(total, listOf(0.62, 0.41, 0.3, 0.15))
  val progress = DownloadProgress(segments.sumOf { it.downloadedBytes }, total)
  return ListFixtures.row(
    id = "studio",
    state = DownloadState.Paused(progress),
    request = DownloadRequest(
      url = "https://redirector.gvt1.com/edgedl/android/studio/android-studio-mac_arm.dmg",
      connections = 4,
    ),
    now = SampleData.NOW,
  ).copy(segments = segments)
}

/** A file whose server only allows one connection. */
private fun single(): TaskRow {
  val total = 734_003_200L
  val url = "https://mirror.example.org/archive/dataset-2026.tar.zst"
  val segments = split(total, listOf(0.38))
  val source = ResolvedSource(url, "http", total, false, null, maxSegments = 1)
  val progress = DownloadProgress(segments.sumOf { it.downloadedBytes }, total, 2_400_000)
  return ListFixtures.row(
    id = "single",
    state = DownloadState.Downloading(progress),
    request = DownloadRequest(url = url, connections = 8, resolvedSource = source),
    now = SampleData.NOW,
  ).copy(segments = segments)
}

/** A download over [count] connections, each about [done] of the way. */
private fun lanes(count: Int, done: Double): TaskRow {
  val total = 13L * (1L shl 30)
  val segments = split(total, List(count) { done + 0.4 * sin(it * 1.7) * done })
  val progress = DownloadProgress(segments.sumOf { it.downloadedBytes }, total, 41_000_000)
  return ListFixtures.row(
    id = "lanes-$count",
    state = DownloadState.Downloading(progress),
    request = DownloadRequest(
      url = "https://image-net.org/data/train/imagenet-part03.tar",
      connections = count,
      priority = DownloadPriority.HIGH,
    ),
    now = SampleData.NOW,
  ).copy(segments = segments)
}

/** Rates of the unfinished [segments], the fourth stalled and the seventh stuck. */
private fun rates(segments: List<Segment>): List<SegmentRate> =
  segments.filter { !it.isComplete }.mapIndexed { index, segment ->
    when (index) {
      3 -> SegmentRate(segment.start, 0, stalledFor = 5.seconds)
      6 -> SegmentRate(segment.start, 0, stalledFor = 14.seconds)
      else -> SegmentRate(segment.start, 1_200_000L + (sin(index * 2.3) * 900_000).roundToLong())
    }
  }

/** A season of a show: 14 files in a folder, some done. */
private fun season(): TaskRow {
  val episodes = (1..12).map { "The.Show.S01E${it.toString().padStart(2, '0')}.1080p.mkv" }
  val names = (episodes + listOf("Subs/English.srt", "README.nfo")).map { "The.Show.S01/$it" }
  val sizes = List(12) { 1_180_000_000L + it * 7_340_032L } + listOf(84_000L, 3_000L)
  val done = listOf(1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 1.0, 0.42, 0.18, 0.0, 0.0, 1.0, 1.0)
  return torrent("season", "The.Show.S01", names, sizes, done)
}

/** A pack of 300 files. */
private fun pack(): TaskRow {
  val names = List(300) { "Pack/track-${(it + 1).toString().padStart(3, '0')}.flac" }
  val sizes = List(300) { 24 * (1L shl 20) + it * 37_000L }
  val done = List(300) { if (it < 120) 1.0 else if (it < 126) 0.5 else 0.0 }
  return torrent("pack", "Pack", names, sizes, done)
}

private fun torrent(
  id: String,
  name: String,
  names: List<String>,
  sizes: List<Long>,
  done: List<Double>,
): TaskRow {
  val url = "magnet:?xt=urn:btih:4c7f3e2b9d1a8f6e5c0b7a3d2e1f9c8b7a6d5e4f&dn=$name"
  val files = names.mapIndexed { index, path -> SourceFile(index.toString(), path, sizes[index]) }
  var offset = 0L
  val segments = files.mapIndexed { index, file ->
    val start = offset
    offset += file.size
    Segment(index, start, start + file.size - 1, (file.size * done[index]).toLong())
  }
  val total = sizes.sum()
  val source = ResolvedSource(url, "torrent", total, true, name, 1, files = files)
  val progress = DownloadProgress(segments.sumOf { it.downloadedBytes }, total, 9_400_000)
  return ListFixtures.row(
    id = id,
    state = DownloadState.Downloading(progress),
    request = DownloadRequest(url = url, resolvedSource = source),
    now = SampleData.NOW,
  ).copy(segments = segments)
}

/** [total] bytes split evenly, segment `i` being `done[i]` of the way. */
private fun split(total: Long, done: List<Double>): List<Segment> {
  val size = total / done.size
  return done.mapIndexed { index, fraction ->
    val start = index * size
    val end = if (index == done.lastIndex) total - 1 else start + size - 1
    Segment(index, start, end, ((end - start + 1) * fraction.coerceIn(0.0, 1.0)).toLong())
  }
}

/** Five minutes of [row]'s speed, split between its unfinished connections. */
private fun history(row: TaskRow): SpeedHistory {
  val lanes = row.segments.filter { !it.isComplete }
  var history: SpeedHistory? = null
  for (second in 299 downTo 0) {
    val at = SampleData.NOW - second.seconds
    val parts = lanes.mapIndexed { index, segment ->
      val wave = 0.75 + 0.2 * sin((second + index * 17) * PI / 31)
      val stalled = index == 2 && second < 6 || index == 5 && second < 12
      segment.start to if (stalled) 0L else (1_150_000 * wave).roundToLong()
    }.toMap()
    val speed = parts.values.sum()
    history = history?.plus(speed, at, parts) ?: SpeedHistory.of(speed, at, parts)
  }
  return checkNotNull(history)
}

private val TIMELINE = listOf(
  TimelineEntry(SampleData.NOW - 7.minutes, TimelineKind.Added, "Added"),
  TimelineEntry(SampleData.NOW - 7.minutes + 1.seconds, TimelineKind.Started, "Started"),
  TimelineEntry(SampleData.NOW - 5.minutes, TimelineKind.Changed, "Connections 4 → 8"),
  TimelineEntry(SampleData.NOW - 4.minutes, TimelineKind.Paused, "Paused"),
  TimelineEntry(SampleData.NOW - 3.minutes - 30.seconds, TimelineKind.Resumed, "Resumed"),
  TimelineEntry(SampleData.NOW - 2.minutes, TimelineKind.Changed, "Limit → 8 MB/s"),
  TimelineEntry(SampleData.NOW - 1.minutes, TimelineKind.Changed, "Priority → High")
)
