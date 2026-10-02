package com.linroid.ketch.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.components.StartTimeDialog
import com.linroid.ketch.app.components.startTimeLabel
import com.linroid.ketch.app.components.startTimeOptions
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

private data class ScheduleOption(
  val label: String,
  val schedule: DownloadSchedule,
)

private val scheduleOptions = listOf(
  ScheduleOption("Now", DownloadSchedule.Immediate),
  ScheduleOption("5 min", DownloadSchedule.AfterDelay(5.minutes)),
  ScheduleOption("15 min", DownloadSchedule.AfterDelay(15.minutes)),
  ScheduleOption("30 min", DownloadSchedule.AfterDelay(30.minutes)),
  ScheduleOption("1 hour", DownloadSchedule.AfterDelay(1.hours)),
  ScheduleOption("3 hours", DownloadSchedule.AfterDelay(3.hours)),
)

/** Button that shows or hides a schedule panel. */
@Composable
fun ScheduleIcon(
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  label: String = "Schedule",
) {
  KetchButton(
    text = label,
    leadingIcon = KetchIcon.Scheduled,
    variant = if (selected) KetchButtonVariant.Secondary else KetchButtonVariant.Ghost,
    size = KetchButtonSize.Small,
    modifier = modifier,
    onClick = onClick,
  )
}

/** Picks when a new download starts: now, or after a delay. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ScheduleSelector(
  value: DownloadSchedule,
  onValueChange: (DownloadSchedule) -> Unit,
  modifier: Modifier = Modifier,
) {
  val spacing = KetchTheme.spacing
  FlowRow(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    verticalArrangement = Arrangement.spacedBy(spacing.s1),
  ) {
    scheduleOptions.forEach { option ->
      KetchChip(
        label = option.label,
        selected = value == option.schedule,
        onClick = { onValueChange(option.schedule) },
      )
    }
  }
}

/**
 * When a task starts: Start now, In 1 hour, Tonight 01:00, Tomorrow 08:00, or a picked date and
 * time. [value] is its current schedule, [onSelect] applies a new one, and [pending] shows that
 * the last change is still being applied.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SchedulePanel(
  value: DownloadSchedule,
  onSelect: (DownloadSchedule) -> Unit,
  modifier: Modifier = Modifier,
  pending: Boolean = false,
) {
  val spacing = KetchTheme.spacing
  val zone = TimeZone.currentSystemDefault()
  val options = startTimeOptions(Clock.System.now(), zone)
  var picking by remember { mutableStateOf(false) }
  Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
    if (value != DownloadSchedule.Immediate) {
      Text(
        text = startTimeLabel(value, Clock.System.now(), zone),
        style = KetchTheme.typography.caption,
        color = KetchTheme.colors.textSecondary,
      )
    }
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      verticalArrangement = Arrangement.spacedBy(spacing.s1),
      itemVerticalAlignment = Alignment.CenterVertically,
    ) {
      options.forEachIndexed { index, option ->
        KetchChip(
          label = option.label,
          selected = false,
          // Times count from the click, not from when the panel opened.
          onClick = { onSelect(startTimeOptions(Clock.System.now(), zone)[index].schedule) },
        )
      }
      KetchChip(label = "Pick date & time…", selected = false, onClick = { picking = true })
      if (pending) KetchSpinner()
    }
  }
  if (picking) {
    StartTimeDialog(
      zone = zone,
      onPicked = {
        picking = false
        onSelect(DownloadSchedule.AtTime(it))
      },
      onCancel = { picking = false },
    )
  }
}
