package com.linroid.ketch.app.ui.common

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchSegmented
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.priorityLabel

/** Button that shows or hides a priority panel; [active] while the priority is not Normal. */
@Composable
fun PriorityIcon(
  active: Boolean,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  label: String = "Priority",
) {
  KetchButton(
    text = label,
    leadingIcon = KetchIcon.Filter,
    variant = if (active || selected) KetchButtonVariant.Secondary else KetchButtonVariant.Ghost,
    size = KetchButtonSize.Small,
    modifier = modifier,
    onClick = onClick,
  )
}

/** Picks one of the [DownloadPriority] values. */
@Composable
fun PrioritySelector(
  value: DownloadPriority,
  onValueChange: (DownloadPriority) -> Unit,
  modifier: Modifier = Modifier,
) {
  KetchSegmented(
    options = DownloadPriority.entries,
    selected = value,
    onSelect = onValueChange,
    label = ::priorityLabel,
    modifier = modifier,
  )
}

/**
 * The priority of a running or waiting task: [value] is its priority, [onSelect] applies a new
 * one, and [pending] shows that the last change is still being applied.
 */
@Composable
fun PriorityPanel(
  value: DownloadPriority,
  onSelect: (DownloadPriority) -> Unit,
  modifier: Modifier = Modifier,
  pending: Boolean = false,
) {
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
  ) {
    PrioritySelector(value = value, onValueChange = { if (it != value) onSelect(it) })
    if (pending) KetchSpinner()
  }
}
