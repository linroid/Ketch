package com.linroid.ketch.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadTask
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private data class ScheduleOption(
  val label: String,
  val schedule: DownloadSchedule,
)

private val scheduleOptions = listOf(
  ScheduleOption("Now", DownloadSchedule.Immediate),
  ScheduleOption(
    "5 min",
    DownloadSchedule.AfterDelay(5.minutes)
  ),
  ScheduleOption(
    "15 min",
    DownloadSchedule.AfterDelay(15.minutes)
  ),
  ScheduleOption(
    "30 min",
    DownloadSchedule.AfterDelay(30.minutes)
  ),
  ScheduleOption(
    "1 hour",
    DownloadSchedule.AfterDelay(1.hours)
  ),
  ScheduleOption(
    "3 hours",
    DownloadSchedule.AfterDelay(3.hours)
  )
)

@Composable
fun ScheduleIcon(
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  label: String = "Schedule",
) {
  com.linroid.ketch.app.components.KetchButton(
    text = label,
    leadingIcon = com.linroid.ketch.app.icons.KetchIcon.Scheduled,
    variant = if (selected) com.linroid.ketch.app.components.KetchButtonVariant.Secondary
      else com.linroid.ketch.app.components.KetchButtonVariant.Ghost,
    size = com.linroid.ketch.app.components.KetchButtonSize.Small,
    modifier = modifier,
    onClick = onClick,
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleSelector(
  value: DownloadSchedule,
  onValueChange: (DownloadSchedule) -> Unit,
  modifier: Modifier = Modifier,
) {
  FlowRow(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    scheduleOptions.forEach { option ->
      FilterChip(
        selected = value == option.schedule,
        onClick = { onValueChange(option.schedule) },
        label = {
          Text(
            text = option.label,
            style =
              MaterialTheme.typography.labelSmall
          )
        }
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SchedulePanel(
  task: DownloadTask,
  scope: CoroutineScope,
  onScheduled: () -> Unit,
  modifier: Modifier = Modifier,
) {
  FlowRow(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    scheduleOptions.forEach { option ->
      FilterChip(
        selected = false,
        onClick = {
          scope.launch {
            task.reschedule(option.schedule)
            onScheduled()
          }
        },
        label = {
          Text(
            text = option.label,
            style =
              MaterialTheme.typography.labelSmall
          )
        }
      )
    }
  }
}
