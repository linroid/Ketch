package com.linroid.ketch.app.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.SpeedLimitPicker
import com.linroid.ketch.app.icons.KetchIcon

/** Button that shows or hides a speed limit panel; [active] while a limit is set. */
@Composable
fun SpeedLimitIcon(
  active: Boolean,
  selected: Boolean,
  onClick: () -> Unit,
  modifier: Modifier = Modifier,
  label: String = "Speed limit",
) {
  KetchButton(
    text = label,
    leadingIcon = KetchIcon.Speed,
    variant = if (active || selected) KetchButtonVariant.Secondary else KetchButtonVariant.Ghost,
    size = KetchButtonSize.Small,
    modifier = modifier,
    onClick = onClick,
  )
}

/**
 * Picks a speed limit from presets or a custom value, reporting each choice to [onValueChange].
 * A typed value is reported once, on ↩, when the field loses focus or after a pause in typing.
 */
@Composable
fun SpeedLimitSelector(
  value: SpeedLimit,
  onValueChange: (SpeedLimit) -> Unit,
  modifier: Modifier = Modifier,
) {
  SpeedLimitPicker(value = value, onCommit = onValueChange, modifier = modifier)
}

/**
 * The speed limit of a running or waiting task: [value] is its limit, [onCommit] applies a new
 * one, and [pending] shows that the last change is still being applied.
 */
@Composable
fun SpeedLimitPanel(
  value: SpeedLimit,
  onCommit: (SpeedLimit) -> Unit,
  modifier: Modifier = Modifier,
  pending: Boolean = false,
) {
  SpeedLimitPicker(value = value, onCommit = onCommit, modifier = modifier, pending = pending)
}
