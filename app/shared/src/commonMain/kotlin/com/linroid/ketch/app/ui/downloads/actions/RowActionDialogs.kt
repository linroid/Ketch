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
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SpeedUnit
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.formatSpeedAmount
import com.linroid.ketch.app.state.preferredUnit
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.AdaptiveModal
import com.linroid.ketch.app.ui.dialog.RemovalPlan
import com.linroid.ketch.app.ui.dialog.RemoveTasksDialog
import com.linroid.ketch.app.util.displayName
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_cancel
import ketch.app.shared.generated.resources.count_downloads
import ketch.app.shared.generated.resources.downloads_discard_body
import ketch.app.shared.generated.resources.downloads_discard_body_many
import ketch.app.shared.generated.resources.downloads_discard_confirm
import ketch.app.shared.generated.resources.downloads_discard_keep
import ketch.app.shared.generated.resources.downloads_discard_title
import ketch.app.shared.generated.resources.downloads_discard_title_many
import ketch.app.shared.generated.resources.downloads_move_confirm
import ketch.app.shared.generated.resources.downloads_move_title
import ketch.app.shared.generated.resources.downloads_send_confirm
import ketch.app.shared.generated.resources.downloads_send_note
import ketch.app.shared.generated.resources.downloads_send_title
import ketch.app.shared.generated.resources.downloads_speed_caps
import ketch.app.shared.generated.resources.downloads_speed_caps_many
import ketch.app.shared.generated.resources.downloads_speed_invalid
import ketch.app.shared.generated.resources.downloads_speed_limit
import ketch.app.shared.generated.resources.downloads_speed_limit_each
import ketch.app.shared.generated.resources.downloads_speed_placeholder
import ketch.app.shared.generated.resources.downloads_speed_set
import ketch.app.shared.generated.resources.downloads_speed_title
import ketch.app.shared.generated.resources.settings_speed_unlimited
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

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
  val device = if (first.device.capabilities.isRemote) first.device.name else localDeviceNoun()
  RemoveTasksDialog(
    plan = remember(rows) { RemovalPlan.of(rows, canTrash = rows.all(runner::canTrash)) },
    deviceName = device,
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
    stringResource(Res.string.downloads_discard_title)
  } else {
    pluralStringResource(Res.plurals.downloads_discard_title_many, rows.size, rows.size)
  }
  AdaptiveModal(
    onDismissRequest = onDismiss,
    title = { Text(title) },
    dismissButton = {
      KetchButton(
        text = stringResource(Res.string.downloads_discard_keep),
        variant = KetchButtonVariant.Secondary,
        onClick = onDismiss,
      )
    },
    confirmButton = {
      KetchButton(
        text = stringResource(Res.string.downloads_discard_confirm),
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
        stringResource(Res.string.downloads_discard_body, single.name)
      } else {
        stringResource(Res.string.downloads_discard_body_many)
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
    mutableStateOf(current?.takeUnless { it.isUnlimited }?.let(::typedSpeed).orEmpty())
  }
  var invalid by remember { mutableStateOf(false) }
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  val unlimited = stringResource(Res.string.settings_speed_unlimited)
  val apply = {
    val limit = parseSpeedInput(text, SpeedUnit.MB, unlimited)
    if (limit == null) {
      invalid = true
    } else {
      onConfirm(limit)
      onDismiss()
    }
  }
  val single = rows.singleOrNull()
  AdaptiveModal(
    onDismissRequest = onDismiss,
    title = { Text(stringResource(Res.string.downloads_speed_title)) },
    dismissButton = {
      KetchButton(
        text = stringResource(Res.string.action_cancel),
        variant = KetchButtonVariant.Secondary,
        onClick = onDismiss,
      )
    },
    confirmButton = {
      KetchButton(text = stringResource(Res.string.downloads_speed_set), onClick = apply)
    },
  ) {
    Text(
      text = if (single != null) {
        stringResource(Res.string.downloads_speed_caps, single.name)
      } else {
        pluralStringResource(Res.plurals.downloads_speed_caps_many, rows.size, rows.size)
      },
      style = KetchTheme.typography.body,
      color = KetchTheme.colors.textSecondary,
    )
    KetchTextField(
      value = text,
      onValueChange = {
        text = it
        invalid = false
      },
      placeholder = stringResource(Res.string.downloads_speed_placeholder),
      label = if (single != null) {
        stringResource(Res.string.downloads_speed_limit)
      } else {
        stringResource(Res.string.downloads_speed_limit_each)
      },
      error = if (invalid) stringResource(Res.string.downloads_speed_invalid) else null,
      keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
      keyboardActions = KeyboardActions(onDone = { apply() }),
      modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
  }
}

/** [limit] as the speed field shows it to edit, in the units it reads back: "1.5 MB/s". */
private fun typedSpeed(limit: SpeedLimit): String {
  val unit = preferredUnit(limit)
  return "${formatSpeedAmount(limit, unit)} ${unit.label}"
}

/**
 * Asks before a Send to whose downloads carry cookies or a sign-in, which the other device keeps
 * with them; see [AppState.sendTo]. Place it once next to the list.
 */
@Composable
internal fun SendConfirmationDialog(state: AppState) {
  val pending = state.sendConfirmation ?: return
  val task = pending.tasks.singleOrNull()
  val what = task?.let { displayName(it.requestState.value, it.state.value) }
    ?: pluralStringResource(Res.plurals.count_downloads, pending.tasks.size, pending.tasks.size)
  // A device name such as NAS-Basement reads as one word, never broken at its hyphen.
  val device = pending.target.label
  val whole = device.replace("-", "-$WORD_JOINER")
  val title = if (pending.move) Res.string.downloads_move_title else Res.string.downloads_send_title
  val verb = if (pending.move) {
    Res.string.downloads_move_confirm
  } else {
    Res.string.downloads_send_confirm
  }
  AdaptiveModal(
    onDismissRequest = state::dismissSendConfirmation,
    title = { Text(stringResource(title, what, whole)) },
    dismissButton = {
      KetchButton(
        text = stringResource(Res.string.action_cancel),
        variant = KetchButtonVariant.Secondary,
        onClick = state::dismissSendConfirmation,
      )
    },
    confirmButton = { KetchButton(text = stringResource(verb), onClick = state::confirmSend) },
  ) {
    Text(
      text = pending.warningText.resolve().replace(device, whole),
      style = KetchTheme.typography.body,
      color = KetchTheme.colors.textSecondary,
    )
    Text(
      text = stringResource(Res.string.downloads_send_note),
      style = KetchTheme.typography.bodyS,
      color = KetchTheme.colors.textTertiary,
    )
  }
}

private const val WORD_JOINER = "⁠"
