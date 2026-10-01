package com.linroid.ketch.app.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.KetchSpeedChart
import com.linroid.ketch.app.components.LaneStrip
import com.linroid.ketch.app.components.LaneStripDefaults
import com.linroid.ketch.app.components.SpeedBand
import com.linroid.ketch.app.state.TaskListModel
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText
import com.linroid.ketch.app.ui.downloads.KetchLayoutInfo
import com.linroid.ketch.app.util.averageSpeed
import com.linroid.ketch.app.util.formatBytes
import com.linroid.ketch.app.util.formatDuration
import com.linroid.ketch.app.util.priorityLabel

/**
 * Details revealed when a download row is expanded: its file, link, size and, for a failure,
 * the problem explained. While downloading it also shows where each connection is in the file
 * and the speed of the last minute, side by side on wide layouts and stacked on narrow ones.
 */
@Composable
internal fun DownloadExpandedPanel(row: TaskRow, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val panel = modifier
    .fillMaxWidth()
    .background(colors.surfaceSunken)
    .padding(spacing.s4)

  if (row.state !is DownloadState.Downloading) {
    Box(panel) { MetadataGrid(row) }
    return
  }

  BoxWithConstraints(panel) {
    val segments = row.segments
    val segmentContent: @Composable () -> Unit = {
      Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
        SectionEyebrow(
          if (segments.isEmpty()) "Segments" else "Segments · ${segments.size} connections",
        )
        if (segments.isEmpty()) {
          PanelNote("No segment data available.")
        } else {
          LaneStrip(
            state = row.state,
            segments = segments,
            height = LaneStripDefaults.MapHeight,
            modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }
    val speedContent: @Composable () -> Unit = {
      Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
        SectionEyebrow("Speed · last minute")
        val chart = Modifier.fillMaxWidth().height(spacing.s16)
        if (row.speedSamples.size >= 2) {
          KetchSpeedChart(
            bands = listOf(SpeedBand(row.speedSamples, colors.accent)),
            modifier = chart,
            slots = TaskListModel.SPEED_SAMPLES,
            showAxis = false,
          )
        } else {
          Box(chart, contentAlignment = Alignment.Center) { PanelNote("Waiting for speed data") }
        }
        MetadataGrid(row)
      }
    }
    if (maxWidth < KetchLayoutInfo.MediumWidth) {
      Column(verticalArrangement = Arrangement.spacedBy(spacing.s5)) {
        segmentContent()
        speedContent()
      }
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(spacing.s6)) {
        Box(Modifier.weight(1f)) { segmentContent() }
        Box(Modifier.weight(1f)) { speedContent() }
      }
    }
  }
}

@Composable
private fun SectionEyebrow(text: String) {
  Text(
    text = eyebrowText(text),
    style = KetchTheme.typography.eyebrow,
    color = KetchTheme.colors.textTertiary,
  )
}

@Composable
private fun PanelNote(text: String) {
  Text(text = text, style = KetchTheme.typography.caption, color = KetchTheme.colors.textTertiary)
}

@Composable
private fun MetadataGrid(row: TaskRow) {
  val colors = KetchTheme.colors
  val completed = row.state as? DownloadState.Completed
  SelectionContainer {
    Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1)) {
      MetaRow("File", row.name)
      MetaRow("URL", row.request.url)
      MetaRow("Priority", priorityLabel(row.request.priority))
      val savedTo = completed?.outputPath ?: row.request.destination?.value
      if (!savedTo.isNullOrBlank()) MetaRow("Saved to", savedTo)
      row.sizeBytes?.let { MetaRow("Size", formatBytes(it)) }
      val downloadTime = completed?.downloadTime
      if (downloadTime != null) {
        MetaRow("Time spent", formatDuration(downloadTime))
        val average = row.sizeBytes?.let { averageSpeed(it, downloadTime) }
        if (average != null) MetaRow("Avg speed", "${formatBytes(average)}/s")
      }
      row.content.error?.let { error ->
        MetaRow("Error", error.title, valueColor = colors.status.failed.color)
        error.hint?.let { MetaRow("Why", it) }
        error.details?.let { MetaRow("Details", it) }
      }
    }
  }
}

@Composable
private fun MetaRow(
  label: String,
  value: String,
  valueColor: Color = KetchTheme.colors.textSecondary,
) {
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
  ) {
    Text(
      text = label,
      style = type.caption,
      color = KetchTheme.colors.textTertiary,
      modifier = Modifier.widthIn(min = spacing.s16),
    )
    Text(
      text = value,
      style = type.mono,
      color = valueColor,
      modifier = Modifier.weight(1f),
    )
  }
}
