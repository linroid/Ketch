package com.linroid.ketch.app.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.deviceNameOrNull
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.platform.localDeviceNounInSentence
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.IncomingDownload
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.AdaptiveModal
import com.linroid.ketch.app.util.PairingLink
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_connect
import ketch.app.shared.generated.resources.connect_not_now
import ketch.app.shared.generated.resources.connect_pairing_has_code
import ketch.app.shared.generated.resources.connect_pairing_known
import ketch.app.shared.generated.resources.connect_pairing_known_code
import ketch.app.shared.generated.resources.connect_pairing_new
import ketch.app.shared.generated.resources.connect_pairing_title
import org.jetbrains.compose.resources.stringResource

/**
 * Asks before connecting to the device a pairing link opened from outside the app names, such
 * as a code scanned with the camera: "Connect to Lins-MacBook-Pro?". Connecting tries the
 * device, adds it (or gives one already added the link's code) and shows it; [onDone] runs once
 * the user connected or declined.
 */
@Composable
internal fun PairingSheet(
  state: AppState,
  pairing: IncomingDownload.Pairing,
  link: PairingLink,
  onDone: () -> Unit,
) {
  val form = remember(pairing) { ConnectForm(pairing.link) }
  val connector = rememberDeviceConnector(state)
  val scope = rememberCoroutineScope()
  val instances by state.instances.collectAsState()
  val known = instances.filterIsInstance<RemoteInstance>()
    .firstOrNull { it.host == link.host && it.port == link.port }
  PairingDialog(
    form = form,
    link = link,
    known = known,
    onSubmit = { check ->
      form.connect(scope, connector, check) { device ->
        state.reportConnected(device, tried = check)
        onDone()
      }
    },
    onDismiss = {
      form.cancel()
      onDone()
    },
  )
}

/**
 * The [PairingSheet] over [form], without the state it reads.
 *
 * @param link what the pairing link reads as.
 * @param known the device the link names when it is added already.
 */
@Composable
internal fun PairingDialog(
  form: ConnectForm,
  link: PairingLink,
  known: RemoteInstance?,
  onSubmit: (check: Boolean) -> Unit,
  onDismiss: () -> Unit,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  val name = deviceNameOrNull(link.name) ?: known?.remoteConfig?.name ?: link.host
  val noun = localDeviceNounInSentence().resolve()
  AdaptiveModal(
    onDismissRequest = onDismiss,
    dismissible = !form.connecting,
    contentSpacing = spacing.s4,
    title = { Text(stringResource(Res.string.connect_pairing_title, name)) },
    confirmButton = {
      val focus = remember { FocusRequester() }
      // Return connects on desktop, as in a system dialog.
      LaunchedEffect(Unit) { if (!isMobilePlatform) focus.requestFocus() }
      KetchButton(
        text = stringResource(Res.string.action_connect),
        onClick = { onSubmit(true) },
        loading = form.connecting,
        modifier = Modifier.focusRequester(focus),
      )
    },
    dismissButton = {
      KetchButton(
        text = stringResource(Res.string.connect_not_now),
        onClick = onDismiss,
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
      DevicePennant(deviceId = link.address, name = name, size = DevicePennantDefaults.Large)
      Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
        Text(
          text = name,
          style = type.titleM,
          color = colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = if (link.secure) "${link.address} · HTTPS" else link.address,
          style = type.mono,
          color = colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Text(
      text = when {
        known == null -> stringResource(Res.string.connect_pairing_new, noun)
        link.token == null -> stringResource(Res.string.connect_pairing_known, name)
        else -> stringResource(Res.string.connect_pairing_known_code, name)
      },
      style = type.bodyS,
      color = colors.textSecondary,
    )
    if (link.token != null && form.problem == null) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      ) {
        KetchIconImage(
          icon = KetchIcon.CheckCircle,
          size = KetchTheme.density.controlGlyph,
          tint = colors.status.completed.color,
        )
        Text(
          text = stringResource(Res.string.connect_pairing_has_code),
          style = type.caption,
          color = colors.textSecondary,
        )
      }
    }
    AccessCodeField(form, onSubmit = { onSubmit(true) })
    ConnectProblemNotice(form.problem, onAddAnyway = { onSubmit(false) })
  }
}
