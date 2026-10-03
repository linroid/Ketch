package com.linroid.ketch.app.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.i18n.SEPARATOR
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.instance.PairingAsk
import com.linroid.ketch.app.platform.localDeviceNounInSentence
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.AdaptiveModal
import com.linroid.ketch.app.ui.devices.systemDeviceType
import com.linroid.ketch.app.ui.devices.systemName
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.pairing_request_allow
import ketch.app.shared.generated.resources.pairing_request_check
import ketch.app.shared.generated.resources.pairing_request_deny
import ketch.app.shared.generated.resources.pairing_request_note
import ketch.app.shared.generated.resources.pairing_request_title
import org.jetbrains.compose.resources.stringResource

/**
 * Asks the owner of this device, while it is shared, about each device that found it on the
 * network and wants its access code: one request at a time, oldest first.
 */
@Composable
internal fun PairingApprovalHost(state: AppState) {
  val requests = state.instanceManager.pairingRequests
  val pending by requests.pending.collectAsState()
  val ask = pending.firstOrNull() ?: return
  key(ask.id) {
    PairingApprovalDialog(ask, onAnswer = { allow -> requests.answer(ask.id, allow) })
  }
}

/**
 * Whether to let [ask]'s device control this one, with the code it shows, so the owner can
 * tell it is the device in their hand. Allow is not focused, so Return does not let a device in
 * by accident.
 *
 * @param onAnswer receives `true` to let the device in.
 */
@Composable
internal fun PairingApprovalDialog(ask: PairingAsk, onAnswer: (Boolean) -> Unit) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  val noun = localDeviceNounInSentence().resolve()
  AdaptiveModal(
    onDismissRequest = { onAnswer(false) },
    dismissible = false,
    contentSpacing = spacing.s4,
    title = { Text(stringResource(Res.string.pairing_request_title, ask.name, noun)) },
    confirmButton = {
      KetchButton(
        text = stringResource(Res.string.pairing_request_allow),
        onClick = { onAnswer(true) },
      )
    },
    dismissButton = {
      KetchButton(
        text = stringResource(Res.string.pairing_request_deny),
        onClick = { onAnswer(false) },
        variant = KetchButtonVariant.Secondary,
      )
    },
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      modifier = Modifier
        .fillMaxWidth()
        .background(colors.surfaceSunken, KetchTheme.shapes.md)
        .padding(spacing.s3),
    ) {
      DevicePennant(
        deviceId = ask.address,
        name = ask.name,
        size = DevicePennantDefaults.Large,
        fallbackType = ask.os?.let(::systemDeviceType),
      )
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
        Text(
          text = ask.name,
          style = type.titleM,
          color = colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = listOfNotNull(ask.os?.let(::systemName), ask.address).joinToString(SEPARATOR),
          style = type.caption,
          color = colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Text(
      text = stringResource(Res.string.pairing_request_check, ask.name),
      style = type.bodyS,
      color = colors.textSecondary,
    )
    Text(
      text = ask.code,
      style = type.numeralXL,
      color = colors.textPrimary,
      textAlign = TextAlign.Center,
      modifier = Modifier.fillMaxWidth(),
    )
    Text(
      text = stringResource(Res.string.pairing_request_note),
      style = type.caption,
      color = colors.textTertiary,
    )
  }
}
