package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.components.StartTimeDialog
import com.linroid.ketch.app.components.parseSpeedInput
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SpeedUnit
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.AdaptiveModal
import com.linroid.ketch.app.ui.dialog.RemovalPlan
import com.linroid.ketch.app.ui.dialog.RemoveTasksDialog

/**
 * Shows the dialog [runner] asks for, if any: Remove with its files, Stop and discard progress,
 * a custom speed limit or a start date and time. Place it once next to the list.
 */
@Composable
internal fun RowActionDialogs(runner: RowActionRunner) {
  when (val dialog = runner.dialog) {
    null -> Unit
    is RowDialog.Remove -> RemoveDialog(dialog, runner)
    is RowDialog.Discard -> DiscardProgressDialog(
      rows = dialog.rows,
      onDismiss = runner::dismissDialog,
      onConfirm = { runner.discard(dialog.rows) },
    )
    is RowDialog.CustomSpeed -> CustomSpeedDialog(
      rows = dialog.rows,
      onDismiss = runner::dismissDialog,
      onConfirm = { limit -> runner.setSpeedLimit(dialog.rows, limit) },
    )
    is RowDialog.PickStart -> StartTimeDialog(
      onPicked = { at ->
        runner.dismissDialog()
        runner.reschedule(dialog.rows, DownloadSchedule.AtTime(at))
      },
      onCancel = runner::dismissDialog,
    )
  }
}

@Composable
private fun RemoveDialog(dialog: RowDialog.Remove, runner: RowActionRunner) {
  val rows = dialog.rows
  val first = rows.first()
  val pulse by runner.state.pulse.state.collectAsState()
  val free = pulse.devices.firstOrNull { it.deviceId == first.key.deviceId }?.disk?.usableBytes
  val deviceName = if (first.device.capabilities.isRemote) first.device.name else localDeviceNoun()
  RemoveTasksDialog(
    plan = remember(rows) { RemovalPlan.of(rows, canTrash = rows.all(runner::canTrash)) },
    deviceName = deviceName,
    withFiles = dialog.withFiles,
    freeBytes = free,
    onDismiss = runner::dismissDialog,
    onConfirm = { withFiles -> runner.remove(rows, withFiles) },
  )
}

/** Asks before discarding the progress of [rows], which cannot be resumed afterwards. */
@Composable
internal fun DiscardProgressDialog(
  rows: List<TaskRow>,
  onDismiss: () -> Unit,
  onConfirm: () -> Unit,
) {
  val single = rows.singleOrNull()
  val title = if (single != null) {
    "Stop and discard progress?"
  } else {
    "Discard the progress of ${downloads(rows.size)}?"
  }
  AdaptiveModal(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    dismissButton = {
      KetchButton(text = "Keep", variant = KetchButtonVariant.Secondary, onClick = onDismiss)
    },
    confirmButton = {
      KetchButton(
        text = "Discard progress",
        variant = KetchButtonVariant.Danger,
        onClick = {
          onConfirm()
          onDismiss()
        },
      )
    },
  ) {
    Text(
      text = if (single != null) {
        "${single.name} can't be resumed after this."
      } else {
        "They can't be resumed after this."
      },
      style = KetchTheme.typography.body,
      color = KetchTheme.colors.textSecondary,
    )
  }
}

/**
 * Asks for a speed limit for [rows], typed as "2m", "500k" or "1.5 MB/s"; a bare number is in
 * MB/s. Applies it once, on Set or ↩.
 */
@Composable
internal fun CustomSpeedDialog(
  rows: List<TaskRow>,
  onDismiss: () -> Unit,
  onConfirm: (SpeedLimit) -> Unit,
) {
  val current = rows.map { it.request.speedLimit }.distinct().singleOrNull()
  var text by remember {
    mutableStateOf(current?.takeUnless { it.isUnlimited }?.let(::formatSpeedLimit).orEmpty())
  }
  var error by remember { mutableStateOf<String?>(null) }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  val apply = {
    val limit = parseSpeedInput(text, SpeedUnit.MB)
    if (limit == null) {
      error = "Type a speed such as 2m or 500k"
    } else {
      onConfirm(limit)
      onDismiss()
    }
  }
  val single = rows.singleOrNull()
  AdaptiveModal(
    onDismissRequest = onDismiss,
    title = { Text("Speed limit") },
    dismissButton = {
      KetchButton(text = "Cancel", variant = KetchButtonVariant.Secondary, onClick = onDismiss)
    },
    confirmButton = { KetchButton(text = "Set limit", onClick = apply) },
  ) {
    Text(
      text = if (single != null) {
        "Caps ${single.name}; the global limit still applies."
      } else {
        "Caps each of ${downloads(rows.size)}; the global limit still applies."
      },
      style = KetchTheme.typography.body,
      color = KetchTheme.colors.textSecondary,
    )
    KetchTextField(
      value = text,
      onValueChange = {
        text = it
        error = null
      },
      placeholder = "2m, 500k or unlimited",
      label = if (single != null) "Limit" else "Limit for each",
      error = error,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { apply() }),
      modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
  }
}

/**
 * Asks before a Send to whose downloads carry cookies or a sign-in, which the other device keeps
 * with them; see [AppState.sendTo]. Place it once next to the list.
 */
@Composable
internal fun SendConfirmationDialog(state: AppState) {
  val pending = state.sendConfirmation ?: return
  val count = pending.tasks.size
  val verb = if (pending.move) "Move" else "Send"
  val what = if (count == 1) "this download" else downloads(count)
  AdaptiveModal(
    onDismissRequest = state::dismissSendConfirmation,
    title = { Text("$verb $what to ${pending.target.label}?") },
    dismissButton = {
      KetchButton(
        text = "Cancel",
        variant = KetchButtonVariant.Secondary,
        onClick = state::dismissSendConfirmation,
      )
    },
    confirmButton = { KetchButton(text = verb, onClick = state::confirmSend) },
  ) {
    Text(
      text = pending.warning,
      style = KetchTheme.typography.body,
      color = KetchTheme.colors.textSecondary,
    )
    Text(
      text = "They are saved with the download there, where anyone who controls it can see them.",
      style = KetchTheme.typography.bodyS,
      color = KetchTheme.colors.textTertiary,
    )
  }
}
