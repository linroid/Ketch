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
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.settings.NoticeTone
import com.linroid.ketch.app.ui.settings.SettingsNotice
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

private val log = KetchLogger("ConnectFields")

/**
 * The "Pairing link or address" field of [form], with what it read from a pairing link under
 * it. Go on the keyboard runs [onSubmit].
 *
 * @param autoFocus takes focus when it appears, except on phones, whose keyboard would cover
 *   the rest.
 */
@Composable
internal fun PairingLinkField(
  form: ConnectForm,
  onSubmit: () -> Unit,
  modifier: Modifier = Modifier,
  placeholder: String = "ketch://pair… or nas.local:8642",
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
      onValueChange = { form.link = it },
      label = "Pairing link or address",
      placeholder = placeholder,
      leadingIcon = KetchIcon.Link,
      mono = true,
      error = form.linkError,
      enabled = !form.connecting,
      onPaste = if (form.link.isEmpty()) {
        {
          scope.launch {
            try {
              clipboard.readText()?.trim()?.let { form.link = it }
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
        Text(summary, style = KetchTheme.typography.caption, color = colors.textSecondary)
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
      label = "Access code",
      placeholder = "Paste or type the code",
      mono = true,
      enabled = !form.connecting,
      error = when {
        request?.rejected != true -> null
        form.code.isBlank() -> "The code in the link no longer works. Enter the current one."
        else -> "That code didn't work. Check it, or copy a new pairing link."
      },
      visualTransformation = PasswordVisualTransformation(),
      keyboardOptions = CodeKeyboard,
      keyboardActions = KeyboardActions(onGo = { onSubmit() }),
      modifier = Modifier.fillMaxWidth().focusRequester(focus),
    )
    if (request != null && !request.rejected && name != null) {
      Text(
        text = "$name asks for its access code. Find it in Settings › Sharing on that device.",
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
  label: String = "Enter details manually",
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
        label = "Host",
        placeholder = "192.168.1.20",
        mono = true,
        error = form.hostError,
        enabled = !form.connecting,
        keyboardOptions = AddressKeyboard,
        keyboardActions = KeyboardActions(onGo = { onSubmit() }),
        modifier = Modifier.weight(1f),
      )
      KetchTextField(
        value = form.port,
        onValueChange = { form.port = it },
        label = "Port",
        mono = true,
        clearable = false,
        error = form.portError,
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
      label = "Use HTTPS",
      modifier = Modifier.fillMaxWidth(),
    )
  }
}

/**
 * Why the last attempt of a form failed, other than a missing code, which [AccessCodeField]
 * explains: nothing answered, with [onAddAnyway] when the device may be added all the same, or
 * it could not be added.
 */
@Composable
internal fun ConnectProblemNotice(
  problem: ConnectProblem?,
  modifier: Modifier = Modifier,
  onAddAnyway: (() -> Unit)? = null,
) {
  when (problem) {
    is ConnectProblem.Unreachable -> SettingsNotice(
      text = "Couldn't reach ${problem.address}. Check that Ketch is sharing there and that " +
        "both devices are on the same network.",
      tone = NoticeTone.Error,
      modifier = modifier,
      action = onAddAnyway?.let { add ->
        {
          KetchButton(
            text = "Add anyway",
            onClick = add,
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
          )
        }
      },
    )
    is ConnectProblem.Failed -> SettingsNotice(
      text = "Couldn't add the device: ${problem.message}",
      tone = NoticeTone.Error,
      modifier = modifier,
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
    messages.post(MessageLevel.Success, "Connected to ${device.label}", deviceId = device.deviceId)
  } else {
    messages.post(
      level = MessageLevel.Info,
      title = "Added ${device.label}",
      detail = "Ketch connects to it once it's reachable",
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
