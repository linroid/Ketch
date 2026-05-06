package com.linroid.ketch.app.ui.list

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.components.KetchSegmentDetail
import com.linroid.ketch.app.components.KetchSpeedChart
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.priorityLabel
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach

/**
 * Detail panel revealed when a download row is expanded. Two columns:
 *  - per-segment progress bars (from [DownloadTask.segments])
 *  - rolling 30-sample speed sparkline + metadata grid
 */
@Composable
fun DownloadExpandedPanel(
  state: DownloadState,
  segments: List<Segment>,
  task: DownloadTask,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography

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

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.surfaceVariant)
      .padding(start = 54.dp, end = 16.dp, top = 12.dp, bottom = 12.dp),
    horizontalArrangement = Arrangement.spacedBy(28.dp),
  ) {
    Column(modifier = Modifier.weight(1f)) {
      SectionEyebrow(
        text = if (segments.isEmpty()) "Segments"
        else "Segments · ${segments.size} parallel connections",
      )
      Spacer(Modifier.height(8.dp))
      if (segments.isEmpty()) {
        Text(
          text = "No active segments yet.",
          style = type.bodySmall,
          color = colors.onSurfaceDim,
        )
      } else {
        val progress = segments.map(::segmentProgress)
        KetchSegmentDetail(
          progress = progress,
          health = List(segments.size) { 1f },
        )
      }
    }

    Column(modifier = Modifier.widthIn(min = 280.dp, max = 360.dp)) {
      SectionEyebrow(text = "Speed · last 30s")
      Spacer(Modifier.height(8.dp))
      if (history.size >= 2) {
        KetchSpeedChart(samples = history.toList(), height = 70.dp)
      } else {
        Box(Modifier.height(70.dp).fillMaxWidth())
      }
      Spacer(Modifier.height(12.dp))
      MetadataGrid(task = task, state = state)
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
private fun MetadataGrid(task: DownloadTask, state: DownloadState) {
  val colors = KetchTheme.colors
  Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    MetaRow("URL", task.request.url, valueColor = colors.onPrimaryContainer)
    MetaRow(
      "Priority",
      "P${task.request.priority.ordinal} · ${priorityLabel(task.request.priority)}",
    )
    val dest = task.request.destination?.value
    if (!dest.isNullOrBlank()) MetaRow("Saved to", dest)
    if (state is DownloadState.Failed) {
      MetaRow("Error", state.error.message.orEmpty(), valueColor = colors.error)
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
      style = type.monoXSmall,
      color = KetchTheme.colors.onSurfaceDim,
      modifier = Modifier.width(70.dp),
    )
    Text(
      text = value,
      style = type.monoSmall,
      color = valueColor,
      maxLines = 2,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

private fun segmentProgress(s: Segment): Float {
  val total = s.totalBytes
  if (total <= 0L) return 0f
  return (s.downloadedBytes.toFloat() / total).coerceIn(0f, 1f)
}
