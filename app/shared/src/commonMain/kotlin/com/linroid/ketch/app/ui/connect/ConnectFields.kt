package com.linroid.ketch.app.ui.connect

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchSwitch
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.DiscoveredServer
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.settings.NoticeTone
import com.linroid.ketch.app.ui.settings.SettingsNotice
import com.linroid.ketch.remote.PairingResult
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.connect_add_anyway
import ketch.app.shared.generated.resources.connect_add_failed
import ketch.app.shared.generated.resources.connect_added
import ketch.app.shared.generated.resources.connect_added_detail
import ketch.app.shared.generated.resources.connect_approval_ask_again
import ketch.app.shared.generated.resources.connect_approval_busy
import ketch.app.shared.generated.resources.connect_approval_denied
import ketch.app.shared.generated.resources.connect_approval_expired
import ketch.app.shared.generated.resources.connect_code_label
import ketch.app.shared.generated.resources.connect_code_link_rejected
import ketch.app.shared.generated.resources.connect_code_placeholder
import ketch.app.shared.generated.resources.connect_code_rejected
import ketch.app.shared.generated.resources.connect_code_requested
import ketch.app.shared.generated.resources.connect_connected
import ketch.app.shared.generated.resources.connect_details_manually
import ketch.app.shared.generated.resources.connect_host
import ketch.app.shared.generated.resources.connect_link_label
import ketch.app.shared.generated.resources.connect_link_placeholder
import ketch.app.shared.generated.resources.connect_port
import ketch.app.shared.generated.resources.connect_unreachable
import ketch.app.shared.generated.resources.connect_use_https
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

private val log = KetchLogger("ConnectFields")

/**
 * The "Pairing link or address" field of [form], with what it read from a pairing link under
 * it. Go on the keyboard runs [onSubmit], and so does pasting a whole pairing link with its
 * access code into the empty field, which leaves nothing else to fill in.
 *
 * @param autoFocus takes focus when it appears, except on phones, whose keyboard would cover
 *   the rest.
 */
@Composable
internal fun PairingLinkField(
  form: ConnectForm,
  onSubmit: () -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = stringResource(Res.string.connect_link_placeholder),
  autoFocus: Boolean = false,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val clipboard = rememberSystemClipboard()
  val scope = rememberCoroutineScope()
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) {
    if (autoFocus && !isMobilePlatform) focus.requestFocus()
  }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
    KetchTextField(
      value = form.link,
      onValueChange = { text ->
        val pasted = form.link.isEmpty() && text.length > 1
        form.link = text
        if (pasted && form.isComplete) onSubmit()
      },
      label = stringResource(Res.string.connect_link_label),
      placeholder = placeholder,
      leadingIcon = KetchIcon.Link,
      mono = true,
      error = form.linkError?.resolve(),
      enabled = !form.connecting,
      onPaste = if (form.link.isEmpty()) {
        {
          scope.launch {
            try {
              clipboard.readText()?.trim()?.let { text ->
                form.link = text
                if (form.isComplete) onSubmit()
              }
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              log.w { "Couldn't read the clipboard: ${e.describeCauses()}" }
            }
          }
        }
      } else {
        null
      },
      keyboardOptions = AddressKeyboard,
      keyboardActions = KeyboardActions(onGo = { onSubmit() }),
      modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    val summary = form.linkSummary
    if (summary != null && form.linkError == null) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s1),
      ) {
        KetchIconImage(
          icon = KetchIcon.CheckCircle,
          size = SummaryGlyph,
          tint = colors.status.completed.color,
        )
        Text(
          text = summary.resolve(),
          style = KetchTheme.typography.caption,
          color = colors.textSecondary,
        )
      }
    }
  }
}

/**
 * The access code field of [form], shown once a device asks for a code, with why it asks
 * under it. It takes focus when it appears.
 */
@Composable
internal fun AccessCodeField(
  form: ConnectForm,
  onSubmit: () -> Unit,
  modifier: Modifier = Modifier,
) {
  if (!form.codeShown) return
  val focus = remember { FocusRequester() }
  LaunchedEffect(Unit) { focus.requestFocus() }
  val request = form.problem as? ConnectProblem.NeedsCode
  val name = form.target?.name ?: request?.address
  Column(modifier, verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1)) {
    KetchTextField(
      value = form.code,
      onValueChange = { form.code = it },
      label = stringResource(Res.string.connect_code_label),
      placeholder = stringResource(Res.string.connect_code_placeholder),
      mono = true,
      enabled = !form.connecting,
      error = when {
        request?.rejected != true -> null
        form.code.isBlank() -> stringResource(Res.string.connect_code_link_rejected)
        else -> stringResource(Res.string.connect_code_rejected)
      },
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = CodeKeyboard,
      keyboardActions = KeyboardActions(onGo = { onSubmit() }),
      modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    if (request != null && !request.rejected && name != null) {
      Text(
        text = stringResource(Res.string.connect_code_requested, name),
        style = KetchTheme.typography.caption,
        color = KetchTheme.colors.textSecondary,
      )
    }
  }
}

/**
 * "Enter details manually" and, while it is open, the host, port and HTTPS fields of [form].
 */
@Composable
internal fun ManualDetails(
  form: ConnectForm,
  onSubmit: () -> Unit,
  modifier: Modifier = Modifier,
  label: String = stringResource(Res.string.connect_details_manually),
) {
  val spacing = KetchTheme.spacing
  Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.s3)) {
    // The padding hangs out to the left, so the chevron lines up with the fields.
    KetchButton(
      text = label,
      onClick = form::toggleManual,
      variant = KetchButtonVariant.Ghost,
      size = KetchButtonSize.Small,
      leadingIcon = if (form.manual) KetchIcon.ChevronDown else KetchIcon.Chevron,
      modifier = Modifier.offset(x = -ghostOverhang()),
    )
    if (!form.manual) return@Column
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.s3)) {
      KetchTextField(
        value = form.host,
        onValueChange = { form.host = it },
        label = stringResource(Res.string.connect_host),
        placeholder = "192.168.1.20",
        mono = true,
        error = form.hostError?.resolve(),
        enabled = !form.connecting,
        keyboardOptions = AddressKeyboard,
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        modifier = Modifier.weight(1f),
      )
      KetchTextField(
        value = form.port,
        onValueChange = { form.port = it },
        label = stringResource(Res.string.connect_port),
        mono = true,
        clearable = false,
        error = form.portError?.resolve(),
        enabled = !form.connecting,
        keyboardOptions = PortKeyboard,
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        modifier = Modifier.width(PortWidth),
      )
    }
    KetchSwitch(
      checked = form.secure,
      onCheckedChange = { form.secure = it },
      enabled = !form.connecting,
      label = stringResource(Res.string.connect_use_https),
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/**
 * Why the last attempt of a form failed, other than a missing code, which [AccessCodeField]
 * explains: nothing answered, with [onAddAnyway] when the device may be added all the same, it
 * could not be added, or its owner did not let this device in, with [onAskAgain] to ask once
 * more.
 */
@Composable
internal fun ConnectProblemNotice(
  problem: ConnectProblem?,
  modifier: Modifier = Modifier,
  onAddAnyway: (() -> Unit)? = null,
  onAskAgain: ((DiscoveredServer) -> Unit)? = null,
) {
  when (problem) {
    is ConnectProblem.Unreachable -> SettingsNotice(
      text = stringResource(Res.string.connect_unreachable, problem.address),
      tone = NoticeTone.Error,
      modifier = modifier,
      action = onAddAnyway?.let { add ->
        {
          KetchButton(
            text = stringResource(Res.string.connect_add_anyway),
            onClick = add,
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
          )
        }
      },
    )
    is ConnectProblem.Failed -> SettingsNotice(
      text = Res.string.connect_add_failed.text(problem.message).resolve(),
      tone = NoticeTone.Error,
      modifier = modifier,
    )
    is ConnectProblem.NotPaired -> SettingsNotice(
      text = stringResource(
        when (problem.reason) {
          PairingResult.Busy -> Res.string.connect_approval_busy
          PairingResult.Expired -> Res.string.connect_approval_expired
          else -> Res.string.connect_approval_denied
        },
        problem.name,
      ),
      tone = NoticeTone.Warning,
      modifier = modifier,
      action = onAskAgain?.let { ask ->
        {
          KetchButton(
            text = stringResource(Res.string.connect_approval_ask_again),
            onClick = { ask(problem.server) },
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
          )
        }
      },
    )
    is ConnectProblem.NeedsCode, null -> Unit
  }
}

/**
 * Padding of a small ghost button, which it may hang out by so its label lines up with the
 * content beside it.
 */
@Composable
internal fun ghostOverhang(): Dp {
  val spacing = KetchTheme.spacing
  return if (KetchTheme.density == KetchDensity.Comfortable) spacing.s4 else spacing.s3
}

/** A [DeviceConnector] that adds devices to [state] and shows them there. */
@Composable
internal fun rememberDeviceConnector(state: AppState): DeviceConnector =
  remember(state) { DeviceConnector(state.instanceManager, state::switchInstance) }

/** Tells that [device] is connected, or added when it was not tried. */
internal fun AppState.reportConnected(device: RemoteInstance, tried: Boolean) {
  if (tried) {
    messages.post(
      level = MessageLevel.Success,
      title = Res.string.connect_connected.text(device.displayName),
      deviceId = device.deviceId,
    )
  } else {
    messages.post(
      level = MessageLevel.Info,
      title = Res.string.connect_added.text(device.displayName),
      detail = Res.string.connect_added_detail.text(),
      deviceId = device.deviceId,
    )
  }
}

private val AddressKeyboard = KeyboardOptions(
  autoCorrectEnabled = false,
  keyboardType = KeyboardType.Uri,
  imeAction = ImeAction.Go,
)

private val CodeKeyboard = KeyboardOptions(
  autoCorrectEnabled = false,
  keyboardType = KeyboardType.Password,
  imeAction = ImeAction.Go,
)

private val PortKeyboard = KeyboardOptions(
  keyboardType = KeyboardType.Number,
  imeAction = ImeAction.Go,
)

private val SummaryGlyph = 12.dp
private val PortWidth = 96.dp
