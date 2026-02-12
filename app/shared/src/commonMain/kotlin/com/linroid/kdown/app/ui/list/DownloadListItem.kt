package com.linroid.kdown.app.ui.list

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.kdown.api.DownloadPriority
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.DownloadTask
import com.linroid.kdown.app.ui.common.PriorityBadge
import com.linroid.kdown.app.ui.common.PriorityIcon
import com.linroid.kdown.app.ui.common.PriorityPanel
import com.linroid.kdown.app.ui.common.ScheduleIcon
import com.linroid.kdown.app.ui.common.SchedulePanel
import com.linroid.kdown.app.ui.common.SpeedLimitIcon
import com.linroid.kdown.app.ui.common.SpeedLimitPanel
import com.linroid.kdown.app.ui.common.StatusIndicator
import com.linroid.kdown.app.ui.common.TaskSettingsIcon
import com.linroid.kdown.app.ui.common.TaskSettingsPanel
import com.linroid.kdown.app.util.extractFilename
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

private enum class ExpandedPanel {
  None, SpeedLimit, Priority, Schedule, Settings
}

@Composable
fun DownloadListItem(
  task: DownloadTask,
  scope: CoroutineScope,
  modifier: Modifier = Modifier,
) {
  val state by task.state.collectAsState()
  val fileName = task.request.fileName
    ?: extractFilename(task.request.url)
      .ifBlank { "download" }
  val isDownloading = state is DownloadState.Downloading ||
    state is DownloadState.Pending
  val isPaused = state is DownloadState.Paused
  val showToggles = isDownloading || isPaused ||
    state is DownloadState.Queued ||
    state is DownloadState.Scheduled
  var expanded by remember { mutableStateOf(ExpandedPanel.None) }

  Card(
    onClick = {
      scope.launch {
        if (isDownloading) task.pause()
        else task.resume()
      }
    },
    enabled = isDownloading || isPaused,
    colors = CardDefaults.cardColors(
      containerColor =
        MaterialTheme.colorScheme.surfaceContainer,
      disabledContainerColor =
        MaterialTheme.colorScheme.surfaceContainer
    ),
    modifier = modifier.fillMaxWidth(),
  ) {
    Column(
      modifier = Modifier.padding(
        horizontal = 16.dp, vertical = 14.dp,
      ),
      verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      // Header: status icon + file info + actions
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement =
          Arrangement.spacedBy(12.dp)
      ) {
        StatusIndicator(state)
        Column(
          modifier = Modifier.weight(1f),
          verticalArrangement =
            Arrangement.spacedBy(4.dp)
        ) {
          Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement =
              Arrangement.spacedBy(8.dp)
          ) {
            Text(
              text = fileName,
              style = MaterialTheme.typography.titleMedium,
              fontWeight = FontWeight.SemiBold,
              color = MaterialTheme.colorScheme.onSurface,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier.weight(
                1f, fill = false,
              )
            )
            if (task.request.priority !=
              DownloadPriority.NORMAL
            ) {
              PriorityBadge(task.request.priority)
            }
          }
          Text(
            text = task.request.url,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme
              .onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
        TaskActionButtons(
          state = state,
          onPause = {
            scope.launch { task.pause() }
          },
          onResume = {
            scope.launch { task.resume() }
          },
          onCancel = {
            scope.launch { task.cancel() }
          },
          onRetry = {
            scope.launch { task.resume() }
          }
        )
      }

      // State-specific content
      ProgressSection(
        state = state,
        speedLimit = task.request.speedLimit,
      )

      // Toggle icon row + remove button
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement =
          Arrangement.spacedBy(4.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        if (showToggles) {
          SpeedLimitIcon(
            active =
              !task.request.speedLimit.isUnlimited,
            selected =
              expanded == ExpandedPanel.SpeedLimit,
            onClick = {
              expanded = if (expanded ==
                ExpandedPanel.SpeedLimit
              ) {
                ExpandedPanel.None
              } else {
                ExpandedPanel.SpeedLimit
              }
            }
          )
          PriorityIcon(
            active = task.request.priority !=
              DownloadPriority.NORMAL,
            selected =
              expanded == ExpandedPanel.Priority,
            onClick = {
              expanded = if (expanded ==
                ExpandedPanel.Priority
              ) {
                ExpandedPanel.None
              } else {
                ExpandedPanel.Priority
              }
            }
          )
          ScheduleIcon(
            selected =
              expanded == ExpandedPanel.Schedule,
            onClick = {
              expanded = if (expanded ==
                ExpandedPanel.Schedule
              ) {
                ExpandedPanel.None
              } else {
                ExpandedPanel.Schedule
              }
            }
          )
          TaskSettingsIcon(
            selected =
              expanded == ExpandedPanel.Settings,
            onClick = {
              expanded = if (expanded ==
                ExpandedPanel.Settings
              ) {
                ExpandedPanel.None
              } else {
                ExpandedPanel.Settings
              }
            }
          )
        }
        Spacer(modifier = Modifier.weight(1f))
        IconButton(
          onClick = { scope.launch { task.remove() } },
          modifier = Modifier.size(32.dp),
        ) {
          Icon(
            imageVector = Icons.Outlined.DeleteOutline,
            contentDescription = "Remove",
            modifier = Modifier.size(18.dp),
            tint = MaterialTheme.colorScheme
              .onSurfaceVariant
          )
        }
      }

      if (showToggles) {
        // Expanded panel below icons
        AnimatedContent(
          targetState = expanded,
          transitionSpec = {
            expandVertically() togetherWith shrinkVertically()
          }
        ) { panel ->
          when (panel) {
            ExpandedPanel.SpeedLimit -> SpeedLimitPanel(
              task = task, scope = scope,
            )
            ExpandedPanel.Priority -> PriorityPanel(
              task = task, scope = scope,
            )
            ExpandedPanel.Schedule -> SchedulePanel(
              task = task,
              scope = scope,
              onScheduled = {
                expanded = ExpandedPanel.None
              }
            )
            ExpandedPanel.Settings -> TaskSettingsPanel(
              task = task,
            )
            ExpandedPanel.None -> {}
          }
        }
      }
    }
  }
}
