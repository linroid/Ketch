package com.linroid.ketch.app.ui.list

import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.components.KetchSegmentDetail
import com.linroid.ketch.app.components.KetchSpeedChart
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.priorityLabel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Detail panel revealed when a download row is expanded. Stacks on narrow layouts:
 *  - per-segment progress bars (from [DownloadTask.segments])
 *  - rolling 30-sample speed sparkline + metadata grid
 */
@Composable
fun DownloadExpandedPanel(
  fileName: String,
  state: DownloadState,
  segments: List<Segment>,
  task: DownloadTask,
  onRemoveRequest: () -> Unit,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography

  if (state !is DownloadState.Downloading) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.Top,
      modifier = Modifier
        .fillMaxWidth()
        .background(colors.surfaceVariant)
        .padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 12.dp),
    ) {
      Box(Modifier.weight(1f)) { MetadataGrid(task = task, state = state, fileName = fileName) }
      if (state.isTerminal) KetchIconButton(
        icon = KetchIcon.Trash,
        contentDescription = "Remove download",
        onClick = onRemoveRequest,
      )
    }
    return
  }

  val history = remember(task.taskId) { mutableStateListOf<Float>() }
  DisposableEffect(task.taskId) {
    val scope = MainScope()
    task.state
      .onEach { s ->
        val bps = (s as? DownloadState.Downloading)?.progress?.bytesPerSecond ?: 0L
        history.add(bps.toFloat())
        if (history.size > 30) history.removeAt(0)
      }
      .launchIn(scope)
    onDispose { scope.cancel() }
  }

  BoxWithConstraints(
    modifier = Modifier.fillMaxWidth().background(colors.surfaceVariant).padding(16.dp),
  ) {
    val segmentContent: @Composable () -> Unit = {
      Column {
        SectionEyebrow(if (segments.isEmpty()) "Segments" else "Segments · ${segments.size} connections")
        Spacer(Modifier.height(8.dp))
        if (segments.isEmpty()) {
          Text("No segment data available.", style = type.bodySmall, color = colors.onSurfaceDim)
        } else {
          KetchSegmentDetail(progress = segments.map(::segmentProgress), health = List(segments.size) { 1f })
        }
      }
    }
    val speedContent: @Composable () -> Unit = {
      Column {
        SectionEyebrow("Speed · recent samples")
        Spacer(Modifier.height(8.dp))
        if (history.size >= 2) {
          KetchSpeedChart(samples = history.toList(), height = 70.dp)
        } else {
          Box(Modifier.height(70.dp).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text("Waiting for speed data", style = type.bodySmall, color = colors.onSurfaceDim)
          }
        }
        Spacer(Modifier.height(12.dp))
        MetadataGrid(task = task, state = state, fileName = fileName)
      }
    }
    if (maxWidth < 600.dp) {
      Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
        segmentContent()
        speedContent()
      }
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Box(Modifier.weight(1f)) { segmentContent() }
        Box(Modifier.weight(1f)) { speedContent() }
      }
    }
  }
}

@Composable
private fun SectionEyebrow(text: String) {
  Text(
    text = text.uppercase(),
    style = KetchTheme.typography.labelSmall.copy(
      fontWeight = FontWeight.SemiBold,
      letterSpacing = 0.6.sp,
    ),
    color = KetchTheme.colors.onSurfaceDim,
  )
}

@Composable
private fun MetadataGrid(task: DownloadTask, state: DownloadState, fileName: String) {
  val colors = KetchTheme.colors
  SelectionContainer {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      MetaRow("File", fileName)
      MetaRow("URL", task.request.url)
      MetaRow(
        "Priority",
        priorityLabel(task.request.priority),
      )
      val dest = (state as? DownloadState.Completed)?.outputPath ?: task.request.destination?.value
      if (!dest.isNullOrBlank()) MetaRow("Saved to", dest)
      if (state is DownloadState.Failed) {
        MetaRow("Error", state.error.message.orEmpty(), valueColor = colors.error)
      }
    }
  }
}

@Composable
private fun MetaRow(
  label: String,
  value: String,
  valueColor: Color = KetchTheme.colors.onSurfaceVariant,
) {
  val type = KetchTheme.typography
  Row(verticalAlignment = Alignment.Top) {
    Text(
      text = label,
      style = type.bodySmall,
      color = KetchTheme.colors.onSurfaceDim,
      modifier = Modifier.width(70.dp),
    )
    Text(
      text = value,
      style = type.monoSmall,
      color = valueColor,
      modifier = Modifier.weight(1f),
    )
  }
}

private fun segmentProgress(s: Segment): Float {
  val total = s.totalBytes
  if (total <= 0L) return 0f
  return (s.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
}
