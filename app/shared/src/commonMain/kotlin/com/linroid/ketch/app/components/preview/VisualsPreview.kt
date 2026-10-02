package com.linroid.ketch.app.components.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.components.DeviceOption
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.DeviceTargetChip
import com.linroid.ketch.app.components.KetchFileTypeChip
import com.linroid.ketch.app.components.KetchFileTypeChipDefaults
import com.linroid.ketch.app.components.KetchHueTile
import com.linroid.ketch.app.components.KetchHueTileDefaults
import com.linroid.ketch.app.components.KetchLogoTile
import com.linroid.ketch.app.components.KetchLogoTileDefaults
import com.linroid.ketch.app.components.KetchSpeedChart
import com.linroid.ketch.app.components.LaneStrip
import com.linroid.ketch.app.components.LaneStripDefaults
import com.linroid.ketch.app.components.SailLanesIllustration
import com.linroid.ketch.app.components.SailLanesIllustrationDefaults
import com.linroid.ketch.app.components.SpeedBand
import com.linroid.ketch.app.components.SpeedLimitLine
import com.linroid.ketch.app.components.StatusDot
import com.linroid.ketch.app.components.StatusDotDefaults
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.theme.FileTypeHue
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText
import com.linroid.ketch.app.util.RowStatus
import com.linroid.ketch.config.DensityMode

@Preview
@Composable
private fun VisualsLightCompactPreview() {
  VisualsPreview(darkTheme = false, density = DensityMode.Compact)
}

@Preview
@Composable
private fun VisualsDarkCompactPreview() {
  VisualsPreview(darkTheme = true, density = DensityMode.Compact)
}

@Preview
@Composable
private fun VisualsLightComfortablePreview() {
  VisualsPreview(darkTheme = false, density = DensityMode.Comfortable)
}

@Preview
@Composable
private fun VisualsDarkComfortablePreview() {
  VisualsPreview(darkTheme = true, density = DensityMode.Comfortable)
}

/** Every visual of the component library on one page, in one theme and density. */
@Composable
internal fun VisualsPreview(darkTheme: Boolean, density: DensityMode) {
  KetchTheme(darkTheme = darkTheme, density = density, reduceMotion = true) {
    VisualsGallery()
  }
}

@Composable
private fun VisualsGallery() {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s4),
    modifier = Modifier
      .width(GalleryWidth)
      .background(colors.surface)
      .padding(spacing.s6),
  ) {
    LaneStrips()
    SpeedCharts()
    Section("Status dots") {
      RowStatus.entries.forEach { status -> StatusDot(status, label = status.name) }
      RowStatus.entries.forEach { status -> StatusDot(status, size = StatusDotDefaults.TableSize) }
    }
    Devices()
    Section("Hue tiles") {
      listOf(KetchHueTileDefaults.Small, KetchHueTileDefaults.Medium, KetchHueTileDefaults.Large)
        .forEach { size -> KetchHueTile(KetchIcon.Speed, FileTypeHue.Sky, size = size) }
      KetchHueTile(KetchIcon.Discover, FileTypeHue.Violet, size = KetchHueTileDefaults.Large)
      KetchHueTile(KetchIcon.Server, FileTypeHue.Teal)
      KetchHueTile(KetchIcon.Network, FileTypeHue.Orange)
    }
    Section("File-type chips") {
      listOf(
        KetchFileTypeChipDefaults.TableSize,
        KetchFileTypeChipDefaults.ListSize,
        KetchFileTypeChipDefaults.TouchSize,
        KetchFileTypeChipDefaults.LargeSize,
      ).forEach { size -> KetchFileTypeChip("ubuntu-24.04-desktop-amd64.iso", size = size) }
      KetchFileTypeChip("Big.Buck.Bunny.mkv", size = KetchFileTypeChipDefaults.LargeSize)
      KetchFileTypeChip("q3-report.pdf", size = KetchFileTypeChipDefaults.LargeSize)
      KetchFileTypeChip("blender.dmg", size = KetchFileTypeChipDefaults.LargeSize, showCheck = true)
    }
    Section("Brand") {
      KetchLogoTile(size = KetchLogoTileDefaults.Sidebar)
      KetchLogoTile(size = KetchLogoTileDefaults.Onboarding)
      KetchLogoTile(size = KetchLogoTileDefaults.About)
      SailLanesIllustration()
      SailLanesIllustration(width = SailLanesIllustrationDefaults.CompactWidth)
    }
  }
}

@Composable
private fun LaneStrips() {
  val total = 5_700_000_000L
  val running = previewSegments(total, listOf(1f, 1f, 0.82f, 0.64f, 0.4f, 1f, 0.55f, 0.2f))
  val downloaded = running.sumOf { it.downloadedBytes }
  val progress = DownloadProgress(downloaded, total, bytesPerSecond = 6_400_000)
  val downloading = DownloadState.Downloading(progress)
  Section("Lane strips", fill = true) {
    listOf(
      LaneStripDefaults.RowHeight,
      LaneStripDefaults.CellHeight,
      LaneStripDefaults.HeaderHeight,
      LaneStripDefaults.MapHeight,
    ).forEach { height ->
      LaneStrip(downloading, running, height = height, stalled = setOf(running[7].start))
    }
    LaneStrip(
      state = downloading,
      segments = running,
      height = LaneStripDefaults.MapHeight,
      highlight = running[3].start,
      modifier = Modifier.padding(vertical = KetchTheme.spacing.s1),
    )
    LaneStrip(DownloadState.Paused(progress), running, height = LaneStripDefaults.CellHeight)
    LaneStrip(DownloadState.Queued, running, height = LaneStripDefaults.CellHeight)
    LaneStrip(
      state = DownloadState.Failed(KetchError.Network()),
      segments = running,
      height = LaneStripDefaults.CellHeight,
    )
    LaneStrip(
      state = DownloadState.Completed("/Downloads/ubuntu.iso", totalBytes = total),
      segments = emptyList(),
      height = LaneStripDefaults.CellHeight,
    )
    LaneStrip(
      state = DownloadState.Downloading(DownloadProgress(812_000_000, -1)),
      segments = emptyList(),
      height = LaneStripDefaults.CellHeight,
    )
    LaneStrip(
      state = DownloadState.Downloading(DownloadProgress(2_100_000_000, total)),
      segments = emptyList(),
      height = LaneStripDefaults.CellHeight,
    )
    LaneStrip(
      state = downloading,
      segments = previewSegments(total, List(32) { (it % 5) / 4f }),
      height = LaneStripDefaults.HeaderHeight,
    )
  }
}

@Composable
private fun SpeedCharts() {
  val colors = KetchTheme.colors
  val bands = remember(colors) {
    List(3) { band ->
      SpeedBand(
        samples = List(60) { 900_000L + (it * (band + 3) % 11) * 180_000L },
        color = colors.lanes[band],
      )
    }
  }
  Section("Speed charts", fill = true) {
    KetchSpeedChart(
      bands = bands,
      limits = listOf(SpeedLimitLine(5_242_880, "Task"), SpeedLimitLine(7_340_032, "Global")),
      timeLabel = { "11:42:${(it % 60).toString().padStart(2, '0')}" },
      modifier = Modifier.fillMaxWidth().height(ChartHeight),
    )
    KetchSpeedChart(
      bands = bands.take(1),
      showAxis = false,
      modifier = Modifier.fillMaxWidth().height(CardSparklineHeight),
    )
    KetchSpeedChart(
      bands = bands.take(1),
      showAxis = false,
      modifier = Modifier.size(SparklineWidth, SparklineHeight),
    )
  }
}

@Composable
private fun Devices() {
  val healths = listOf(
    DeviceHealth.Local(sharingPort = 8642),
    DeviceHealth.Live,
    DeviceHealth.Connecting,
    DeviceHealth.Offline(),
    DeviceHealth.Unauthorized
  )
  Section("Pennants") {
    listOf(
      DevicePennantDefaults.XSmall,
      DevicePennantDefaults.Small,
      DevicePennantDefaults.Medium,
      DevicePennantDefaults.Large,
      DevicePennantDefaults.XLarge,
    ).forEach { size -> DevicePennant("nas", "NAS-Basement", size = size) }
    healths.forEachIndexed { i, health ->
      DevicePennant("device-$i", "Lins-MacBook-Pro", health = health, failures = i % 3 * 6)
    }
    DevicePennant(
      deviceId = "den",
      name = "Den-PC",
      size = DevicePennantDefaults.Large,
      health = DeviceHealth.Live,
      icon = KetchIcon.Desktop,
    )
  }
  Section("Device target") {
    val options = listOf(
      DeviceOption(
        id = "local",
        name = "This Mac",
        health = DeviceHealth.Local(),
        pennantName = "Lins-MacBook-Pro",
        summary = "412 GB free · 2 active",
        shortcut = "⌘⌥1",
      ),
      DeviceOption(
        id = "nas",
        name = "NAS-Basement",
        health = DeviceHealth.Live,
        summary = "1.8 TB free · 2 active · Slow lane",
        shortcut = "⌘⌥2",
      ),
      DeviceOption("den", "Den-PC", DeviceHealth.Offline(), shortcut = "⌘⌥3")
    )
    var target by remember { mutableStateOf("local") }
    DeviceTargetChip(selectedId = target, options = options, onSelect = { target = it.id })
  }
}

/** Segments that split [total] bytes evenly, each [progress] of the way done. */
private fun previewSegments(total: Long, progress: List<Float>): List<Segment> {
  val length = total / progress.size
  return progress.mapIndexed { i, fraction ->
    val start = i * length
    val end = if (i == progress.lastIndex) total - 1 else start + length - 1
    Segment(i, start, end, ((end - start + 1) * fraction.toDouble()).toLong())
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Section(title: String, fill: Boolean = false, content: @Composable () -> Unit) {
  val spacing = KetchTheme.spacing
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
    Text(
      text = eyebrowText(title),
      style = KetchTheme.typography.eyebrow,
      color = KetchTheme.colors.textTertiary,
    )
    if (fill) {
      Column(verticalArrangement = Arrangement.spacedBy(spacing.s3)) { content() }
    } else {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
        verticalArrangement = Arrangement.spacedBy(spacing.s3),
        itemVerticalAlignment = Alignment.CenterVertically,
      ) { content() }
    }
  }
}

private val GalleryWidth = 720.dp
private val ChartHeight = 120.dp
private val CardSparklineHeight = 40.dp
private val SparklineWidth = 64.dp
private val SparklineHeight = 14.dp
