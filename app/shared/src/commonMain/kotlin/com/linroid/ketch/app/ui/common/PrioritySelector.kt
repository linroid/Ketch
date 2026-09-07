package com.linroid.ketch.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.LowPriority
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.app.util.priorityLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

@Composable
fun PriorityIcon(
  active: Boolean,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  label: String = "Priority",
) {
  com.linroid.ketch.app.components.KetchButton(
    text = label,
    leadingIcon = com.linroid.ketch.app.icons.KetchIcon.Filter,
    variant = if (active || selected) com.linroid.ketch.app.components.KetchButtonVariant.Secondary
      else com.linroid.ketch.app.components.KetchButtonVariant.Ghost,
    size = com.linroid.ketch.app.components.KetchButtonSize.Small,
    modifier = modifier,
    onClick = onClick,
  )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PrioritySelector(
  value: DownloadPriority,
  onValueChange: (DownloadPriority) -> Unit,
  modifier: Modifier = Modifier,
) {
  FlowRow(
    modifier = modifier,
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    DownloadPriority.entries.forEach { priority ->
      FilterChip(
        selected = value == priority,
        onClick = { onValueChange(priority) },
        label = {
          Text(
            text = priorityLabel(priority),
            style =
              MaterialTheme.typography.labelSmall
          )
        }
      )
    }
  }
}

@Composable
fun PriorityPanel(
  task: DownloadTask,
  scope: CoroutineScope,
  modifier: Modifier = Modifier,
) {
  var currentPriority by remember {
    mutableStateOf(task.request.priority)
  }
  PrioritySelector(
    value = currentPriority,
    onValueChange = { priority ->
      currentPriority = priority
      scope.launch { task.setPriority(priority) }
    },
    modifier = modifier,
  )
}
