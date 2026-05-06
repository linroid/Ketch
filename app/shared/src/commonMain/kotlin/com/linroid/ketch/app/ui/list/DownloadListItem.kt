package com.linroid.ketch.app.ui.list

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.isName
import com.linroid.ketch.app.components.KetchFileTypeChip
import com.linroid.ketch.app.components.KetchProgressBar
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.LocalDownloadStateColors
import com.linroid.ketch.app.theme.StateColorPair
import com.linroid.ketch.app.ui.common.PriorityBadge
import com.linroid.ketch.app.ui.common.PriorityIcon
import com.linroid.ketch.app.ui.common.PriorityPanel
import com.linroid.ketch.app.ui.common.ScheduleIcon
import com.linroid.ketch.app.ui.common.SchedulePanel
import com.linroid.ketch.app.ui.common.SpeedLimitIcon
import com.linroid.ketch.app.ui.common.SpeedLimitPanel
import com.linroid.ketch.app.ui.common.TaskSettingsIcon
import com.linroid.ketch.app.ui.common.TaskSettingsPanel
import com.linroid.ketch.app.util.extractFilename
import com.linroid.ketch.app.util.formatBytes
import com.linroid.ketch.app.util.formatEta
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private enum class ExpandedSubPanel {
  None, SpeedLimit, Priority, Schedule, Settings
}

@Composable
fun DownloadListItem(
  task: DownloadTask,
  scope: CoroutineScope,
  modifier: Modifier = Modifier,
) {
  val state by task.state.collectAsState()
  val segments by task.segments.collectAsState()
  val dest = task.request.destination
  val fileName = remember(task.taskId, dest, task.request.url) {
    val raw = when {
      dest != null && dest.isName() -> dest.value
      dest != null -> extractFilename(dest.value).ifBlank { null }
      else -> null
    }
    raw ?: extractFilename(task.request.url).ifBlank { "download" }
  }

  var expanded by remember { mutableStateOf(false) }
  var subPanel by remember { mutableStateOf(ExpandedSubPanel.None) }

  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val stateColors = LocalDownloadStateColors.current.forState(state)
  val borderColor = if (expanded) colors.outline else colors.outlineVariant

  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(10.dp))
      .background(if (expanded) colors.surface else colors.surface)
      .border(1.dp, borderColor, RoundedCornerShape(10.dp))
      .clickable { expanded = !expanded },
  ) {
    DownloadRow(
      fileName = fileName,
      state = state,
      stateColors = stateColors,
      task = task,
      scope = scope,
    )

    AnimatedVisibility(
      visible = expanded,
      enter = expandVertically() + fadeIn(),
      exit = shrinkVertically() + fadeOut(),
    ) {
      Column {
        DownloadExpandedPanel(
          state = state,
          segments = segments,
          task = task,
        )

        ExpandedSettingsRow(
          task = task,
          scope = scope,
          subPanel = subPanel,
          onSubPanelChange = { subPanel = it },
        )

        AnimatedContent(
          targetState = subPanel,
          transitionSpec = {
            (expandVertically() + fadeIn()) togetherWith
              (shrinkVertically() + fadeOut())
          },
          label = "sub-panel",
        ) { panel ->
          when (panel) {
            ExpandedSubPanel.SpeedLimit -> SpeedLimitPanel(task, scope)
            ExpandedSubPanel.Priority -> PriorityPanel(task, scope)
            ExpandedSubPanel.Schedule -> SchedulePanel(
              task = task,
              scope = scope,
              onScheduled = { subPanel = ExpandedSubPanel.None },
            )
            ExpandedSubPanel.Settings -> TaskSettingsPanel(task)
            ExpandedSubPanel.None -> {}
          }
        }
      }
    }
  }
}

@Composable
private fun DownloadRow(
  fileName: String,
  state: DownloadState,
  stateColors: StateColorPair,
  task: DownloadTask,
  scope: CoroutineScope,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val progress = stateProgress(state)
  val animatedPct by animateFloatAsState(progress, tween(400), label = "row-progress")

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(12.dp),
    modifier = Modifier
      .fillMaxWidth()
      .height(56.dp)
      .padding(horizontal = 16.dp),
  ) {
    KetchFileTypeChip(fileName)

    // Name + thin progress
    Column(
      verticalArrangement = Arrangement.spacedBy(6.dp),
      modifier = Modifier.weight(1f),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
          text = fileName,
          style = type.bodyLarge.copy(fontWeight = FontWeight.Medium),
          color = colors.onBackground,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.weight(1f, fill = false),
        )
        if (task.request.priority != DownloadPriority.NORMAL) {
          Spacer(Modifier.width(8.dp))
          PriorityBadge(task.request.priority)
        }
      }
      KetchProgressBar(
        progress = animatedPct,
        fillColor = if (state is DownloadState.Completed) Color.Transparent
        else stateColors.foreground,
      )
    }

    // Primary metric (mono)
    PrimaryMetric(state = state)

    // Status pill
    StatusPill(state = state, foreground = stateColors.foreground)

    // Single contextual action
    ContextualAction(state = state, task = task, scope = scope)
  }
}

@Composable
private fun PrimaryMetric(state: DownloadState) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val text = when (state) {
    is DownloadState.Downloading -> {
      val p = state.progress
      val speed = if (p.bytesPerSecond > 0) "${formatBytes(p.bytesPerSecond)}/s" else "--"
      val eta = if (p.bytesPerSecond > 0 && p.totalBytes > 0) {
        val remaining = (p.totalBytes - p.downloadedBytes).coerceAtLeast(0)
        formatEta(remaining / p.bytesPerSecond)
      } else ""
      if (eta.isNotEmpty()) "$speed · $eta" else speed
    }
    is DownloadState.Paused -> {
      val p = state.progress
      if (p.totalBytes > 0) "${formatBytes(p.downloadedBytes)} / ${formatBytes(p.totalBytes)}"
      else "Paused"
    }
    is DownloadState.Queued -> "Queued"
    is DownloadState.Scheduled -> "Scheduled"
    is DownloadState.Completed -> ""
    is DownloadState.Failed -> "Failed"
    is DownloadState.Canceled -> "Canceled"
  }
  Text(
    text = text,
    style = type.monoSmall,
    color = colors.onSurfaceVariant,
    maxLines = 1,
    overflow = TextOverflow.Ellipsis,
    modifier = Modifier.widthIn(max = 160.dp),
  )
}

@Composable
private fun StatusPill(state: DownloadState, foreground: Color) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography

  val (label, isLive) = when (state) {
    is DownloadState.Downloading -> {
      val pct = (state.progress.percent * 100).coerceIn(0f, 100f)
      "${pct.toInt()}%" to true
    }
    is DownloadState.Paused -> "Paused" to false
    is DownloadState.Queued -> "Queued" to false
    is DownloadState.Scheduled -> "Scheduled" to false
    is DownloadState.Completed -> "Done" to false
    is DownloadState.Failed -> "Failed" to false
    is DownloadState.Canceled -> "Canceled" to false
  }

  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    modifier = Modifier.widthIn(min = 88.dp),
  ) {
    Box(
      modifier = Modifier
        .size(6.dp)
        .clip(CircleShape)
        .background(foreground)
        .let {
          if (isLive) it.border(
            width = 3.dp,
            color = foreground.copy(alpha = 0.18f),
            shape = CircleShape,
          ) else it
        },
    )
    Text(
      text = label,
      style = type.labelMedium.copy(fontWeight = FontWeight.Medium),
      color = foreground,
      maxLines = 1,
    )
  }
}

@Composable
private fun ContextualAction(
  state: DownloadState,
  task: DownloadTask,
  scope: CoroutineScope,
) {
  val colors = KetchTheme.colors
  Box(modifier = Modifier.size(32.dp), contentAlignment = Alignment.Center) {
    when (state) {
      is DownloadState.Downloading -> RowAction(KetchIcon.Pause, colors.onSurfaceVariant) {
        scope.launch { task.pause() }
      }
      is DownloadState.Paused -> RowAction(KetchIcon.Play, colors.primary) {
        scope.launch { task.resume() }
      }
      is DownloadState.Queued -> RowAction(KetchIcon.More, colors.onSurfaceVariant) {}
      is DownloadState.Scheduled -> RowAction(KetchIcon.Scheduled, colors.warning) {}
      is DownloadState.Completed -> RowAction(KetchIcon.Folder, colors.onSurfaceVariant) {}
      is DownloadState.Failed,
      is DownloadState.Canceled -> RowAction(KetchIcon.Retry, colors.primary) {
        scope.launch { task.resume() }
      }
    }
  }
}

@Composable
private fun RowAction(icon: KetchIcon, tint: Color, onClick: () -> Unit) {
  Box(
    modifier = Modifier
      .size(30.dp)
      .clip(RoundedCornerShape(7.dp))
      .clickable(onClick = onClick),
    contentAlignment = Alignment.Center,
  ) {
    com.linroid.ketch.app.icons.KetchIconImage(icon = icon, size = 16.dp, tint = tint)
  }
}

@Composable
private fun ExpandedSettingsRow(
  task: DownloadTask,
  scope: CoroutineScope,
  subPanel: ExpandedSubPanel,
  onSubPanelChange: (ExpandedSubPanel) -> Unit,
) {
  val state by task.state.collectAsState()
  val canConfigure = state is DownloadState.Downloading ||
    state is DownloadState.Paused ||
    state is DownloadState.Queued ||
    state is DownloadState.Scheduled
  if (!canConfigure) return

  fun toggle(target: ExpandedSubPanel) {
    onSubPanelChange(if (subPanel == target) ExpandedSubPanel.None else target)
  }

  Row(
    modifier = Modifier
      .fillMaxWidth()
      .padding(start = 16.dp, end = 8.dp, top = 4.dp, bottom = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    SpeedLimitIcon(
      active = !task.request.speedLimit.isUnlimited,
      selected = subPanel == ExpandedSubPanel.SpeedLimit,
      onClick = { toggle(ExpandedSubPanel.SpeedLimit) },
    )
    PriorityIcon(
      active = task.request.priority != DownloadPriority.NORMAL,
      selected = subPanel == ExpandedSubPanel.Priority,
      onClick = { toggle(ExpandedSubPanel.Priority) },
    )
    ScheduleIcon(
      selected = subPanel == ExpandedSubPanel.Schedule,
      onClick = { toggle(ExpandedSubPanel.Schedule) },
    )
    TaskSettingsIcon(
      selected = subPanel == ExpandedSubPanel.Settings,
      onClick = { toggle(ExpandedSubPanel.Settings) },
    )
    Spacer(Modifier.weight(1f))
    com.linroid.ketch.app.components.KetchIconButton(
      icon = KetchIcon.Trash,
      onClick = { scope.launch { task.remove() } },
    )
  }
}

private fun stateProgress(state: DownloadState): Float = when (state) {
  is DownloadState.Downloading -> state.progress.percent
  is DownloadState.Paused -> state.progress.percent
  is DownloadState.Completed -> 1f
  else -> 0f
}
