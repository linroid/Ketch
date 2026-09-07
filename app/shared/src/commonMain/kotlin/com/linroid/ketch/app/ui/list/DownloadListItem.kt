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
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
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
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.isName
import com.linroid.ketch.app.components.KetchIconButton
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
import com.linroid.ketch.app.ui.dialog.RemoveDownloadDialog
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
  val request by task.requestState.collectAsState()
  val state by task.state.collectAsState()
  val segments by task.segments.collectAsState()
  val dest = request.destination
  val fileName = remember(task.taskId, dest, request.url) {
    val raw = when {
      dest != null && dest.isName() -> dest.value
      dest != null -> extractFilename(dest.value).ifBlank { null }
      else -> null
    }
    raw ?: extractFilename(request.url).ifBlank { "download" }
  }

  var expanded by remember { mutableStateOf(false) }
  var subPanel by remember { mutableStateOf(ExpandedSubPanel.None) }
  var showRemoveDialog by remember { mutableStateOf(false) }

  LaunchedEffect(state::class) {
    if (state.isTerminal) subPanel = ExpandedSubPanel.None
  }

  val colors = KetchTheme.colors
  val stateColors = LocalDownloadStateColors.current.forState(state)
  val borderColor = if (expanded) colors.primary.copy(alpha = 0.35f) else colors.outlineVariant

  Column(
    modifier = modifier
      .fillMaxWidth()
      .clip(RoundedCornerShape(14.dp))
      .background(colors.surface)
      .border(1.dp, borderColor, RoundedCornerShape(14.dp)),
  ) {
    DownloadRow(
      fileName = fileName,
      state = state,
      stateColors = stateColors,
      task = task,
      scope = scope,
      expanded = expanded,
      onToggle = { expanded = !expanded },
    )

    AnimatedVisibility(
      visible = expanded,
      enter = expandVertically() + fadeIn(),
      exit = shrinkVertically() + fadeOut(),
    ) {
      Column {
        DownloadExpandedPanel(
          fileName = fileName,
          state = state,
          segments = segments,
          task = task,
          onRemoveRequest = { showRemoveDialog = true },
        )

        ExpandedSettingsRow(
          task = task,
          subPanel = subPanel,
          onSubPanelChange = { subPanel = it },
          onRemoveRequest = { showRemoveDialog = true },
          onCancel = { scope.launch { task.cancel() } },
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

  if (showRemoveDialog) {
    val totalBytes = when (val s = state) {
      is DownloadState.Downloading -> s.progress.totalBytes
      is DownloadState.Paused -> s.progress.totalBytes
      else -> null
    }
    RemoveDownloadDialog(
      fileName = fileName,
      totalBytes = totalBytes,
      onDismiss = { showRemoveDialog = false },
      onConfirm = { deleteFiles ->
        scope.launch { task.remove(deleteFiles = deleteFiles) }
      },
    )
  }
}

@Composable
private fun DownloadRow(
  fileName: String,
  state: DownloadState,
  stateColors: StateColorPair,
  task: DownloadTask,
  scope: CoroutineScope,
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  val request by task.requestState.collectAsState()
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val progress = stateProgress(state)
  val animatedPct by animateFloatAsState(progress, tween(400), label = "row-progress")

  BoxWithConstraints {
    val compact = maxWidth < 600.dp
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(if (compact) 8.dp else 12.dp),
      modifier = Modifier
        .fillMaxWidth()
        .clickable(onClickLabel = if (expanded) "Hide download details" else "Show download details", onClick = onToggle)
        .heightIn(min = 80.dp)
        .padding(horizontal = 16.dp, vertical = 14.dp),
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
          if (request.priority != DownloadPriority.NORMAL) {
            Spacer(Modifier.width(8.dp))
            PriorityBadge(request.priority)
          }
        }
        if (state is DownloadState.Downloading || state is DownloadState.Paused) KetchProgressBar(
          progress = animatedPct,
          fillColor = stateColors.foreground,
        )
        if (compact) {
          FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            StatusPill(state = state, foreground = stateColors.foreground)
            PrimaryMetric(state = state, speedLimit = request.speedLimit)
          }
        }
      }

      // Primary metric (mono)
      if (!compact) {
        PrimaryMetric(state = state, speedLimit = request.speedLimit)
        StatusPill(state = state, foreground = stateColors.foreground)
      }

      // Single contextual action
      ContextualAction(state = state, task = task, scope = scope, expanded = expanded, onToggle = onToggle)
    }
  }
}

@Composable
private fun PrimaryMetric(state: DownloadState, speedLimit: SpeedLimit) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val text = when (state) {
    is DownloadState.Downloading -> {
      val p = state.progress
      val speed = buildString {
        append(if (p.bytesPerSecond > 0) "${formatBytes(p.bytesPerSecond)}/s" else "--")
        if (!speedLimit.isUnlimited) {
          append(" (limit: ${formatBytes(speedLimit.bytesPerSecond)}/s)")
        }
      }
      val eta = if (p.bytesPerSecond > 0 && p.totalBytes > 0) {
        val remaining = (p.totalBytes - p.downloadedBytes).coerceAtLeast(0)
        formatEta(remaining / p.bytesPerSecond)
      } else ""
      if (eta.isNotEmpty()) "$speed · $eta" else speed
    }
    is DownloadState.Paused -> {
      val p = state.progress
      if (p.totalBytes > 0) "${formatBytes(p.downloadedBytes)} / ${formatBytes(p.totalBytes)}"
      else if (p.downloadedBytes > 0) formatBytes(p.downloadedBytes) else ""
    }
    is DownloadState.Queued -> ""
    is DownloadState.Scheduled -> ""
    is DownloadState.Completed -> ""
    is DownloadState.Failed -> ""
    is DownloadState.Canceled -> ""
  }
  if (text.isEmpty()) return
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
  val type = KetchTheme.typography

  val (label, isLive) = when (state) {
    is DownloadState.Downloading -> {
      val pct = (state.progress.percent * 100).coerceIn(0f, 100f)
      (if (state.progress.totalBytes > 0) "${pct.toInt()}%" else "Downloading") to true
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
    modifier = Modifier
      .clip(RoundedCornerShape(6.dp))
      .background(foreground.copy(alpha = 0.09f))
      .padding(horizontal = 8.dp, vertical = 4.dp),
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
  expanded: Boolean,
  onToggle: () -> Unit,
) {
  when (state) {
    is DownloadState.Downloading -> KetchIconButton(
      icon = KetchIcon.Pause, contentDescription = "Pause download",
      onClick = { scope.launch { task.pause() } },
    )
    is DownloadState.Paused -> KetchIconButton(
      icon = KetchIcon.Play, contentDescription = "Resume download",
      onClick = { scope.launch { task.resume() } },
    )
    is DownloadState.Failed, is DownloadState.Canceled -> KetchIconButton(
      icon = KetchIcon.Retry, contentDescription = "Retry download",
      onClick = { scope.launch { task.resume() } },
    )
    else -> KetchIconButton(
      icon = if (expanded) KetchIcon.ChevronDown else KetchIcon.Chevron,
      contentDescription = if (expanded) "Hide download details" else "Show download details",
      onClick = onToggle,
    )
  }
}

@Composable
private fun ExpandedSettingsRow(
  task: DownloadTask,
  subPanel: ExpandedSubPanel,
  onSubPanelChange: (ExpandedSubPanel) -> Unit,
  onRemoveRequest: () -> Unit,
  onCancel: () -> Unit,
) {
  val request by task.requestState.collectAsState()
  val state by task.state.collectAsState()
  val canConfigure = state is DownloadState.Downloading ||
    state is DownloadState.Paused ||
    state is DownloadState.Queued ||
    state is DownloadState.Scheduled

  if (!canConfigure) return

  fun toggle(target: ExpandedSubPanel) {
    onSubPanelChange(if (subPanel == target) ExpandedSubPanel.None else target)
  }

  FlowRow(
    modifier = Modifier
      .fillMaxWidth()
      .padding(horizontal = 12.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(4.dp),
    verticalArrangement = Arrangement.Center,
  ) {
    if (canConfigure) {
      SpeedLimitIcon(
        active = !request.speedLimit.isUnlimited,
        selected = subPanel == ExpandedSubPanel.SpeedLimit,
        onClick = { toggle(ExpandedSubPanel.SpeedLimit) },
      )
      PriorityIcon(
        active = request.priority != DownloadPriority.NORMAL,
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
    }
    if (canConfigure) {
      KetchIconButton(icon = KetchIcon.Stop, contentDescription = "Cancel download", onClick = onCancel)
    }
    com.linroid.ketch.app.components.KetchIconButton(
      icon = KetchIcon.Trash,
      contentDescription = "Remove download",
      onClick = onRemoveRequest,
    )
  }
}

private fun stateProgress(state: DownloadState): Float = when (state) {
  is DownloadState.Downloading -> state.progress.percent
  is DownloadState.Paused -> state.progress.percent
  is DownloadState.Completed -> 1f
  else -> 0f
}
