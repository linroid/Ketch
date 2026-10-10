package com.linroid.ketch.app.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DeviceType
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.ketchClickable
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.DiscoveredServer
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.instance.deviceNameOrNull
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.platform.localDeviceNounInSentence
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.common.AdaptiveModal
import com.linroid.ketch.app.ui.settings.Chevron
import com.linroid.ketch.app.util.PairingLink
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_cancel
import ketch.app.shared.generated.resources.action_connect
import ketch.app.shared.generated.resources.action_show
import ketch.app.shared.generated.resources.connect_add_title
import ketch.app.shared.generated.resources.connect_added_badge
import ketch.app.shared.generated.resources.connect_approval_check
import ketch.app.shared.generated.resources.connect_approval_enter_code
import ketch.app.shared.generated.resources.connect_approval_waiting
import ketch.app.shared.generated.resources.connect_asks_for_approval
import ketch.app.shared.generated.resources.connect_asks_for_code
import ketch.app.shared.generated.resources.connect_code_no_longer_accepted
import ketch.app.shared.generated.resources.connect_find_on_network
import ketch.app.shared.generated.resources.connect_looking
import ketch.app.shared.generated.resources.connect_nearby_failed_hint
import ketch.app.shared.generated.resources.connect_new_code_title
import ketch.app.shared.generated.resources.connect_none_found
import ketch.app.shared.generated.resources.connect_on_your_network
import ketch.app.shared.generated.resources.connect_paste_hint
import ketch.app.shared.generated.resources.connect_scan_hint
import ketch.app.shared.generated.resources.connect_search_again
import ketch.app.shared.generated.resources.connect_searching
import ketch.app.shared.generated.resources.connect_share_instead
import ketch.app.shared.generated.resources.connect_show_pairing_code
import org.jetbrains.compose.resources.stringResource

/**
 * The Add device sheet: connects this app to another Ketch device from its pairing link or
 * address, a device found on the network, or details typed by hand. Each is tried before it is
 * added, so a typo or a missing access code shows in the sheet rather than as an offline
 * device; the access code field appears once a device asks for one.
 *
 * Desktop apps search the network as the sheet opens; phones search when "Find on network" is
 * tapped, which asks Android for its nearby devices permission first. The browser cannot
 * search, so the web hides it. A device found there that asks for a code but lets its owner
 * allow this device instead ([DiscoveredServer.pairable]) is asked to, while the sheet shows
 * the code the owner checks.
 *
 * @param device a device that rejected its access code; the sheet then asks for a new one.
 * @param searchNow searches the network as the sheet opens on phones too, for a "Find on
 *   network" tapped before it opened.
 */
@Composable
fun AddDeviceSheet(
  state: AppState,
  onDismiss: () -> Unit,
  device: RemoteInstance? = null,
  searchNow: Boolean = false,
) {
  val form = remember(device) { device?.let(::codeForm) ?: ConnectForm() }
  val connector = rememberDeviceConnector(state)
  val scope = rememberCoroutineScope()
  val nearby = rememberNearbySearch().takeIf { it.supported && device == null }
  val access = rememberNearbyAccess()
  val instances by state.instances.collectAsState()
  val serverState by state.serverState.collectAsState()
  LaunchedEffect(nearby) {
    // Asking for a permission is up to the user, so phones wait for a tap.
    if (nearby != null && (searchNow || !isMobilePlatform)) {
      access.request(search = true) { nearby.search() }
    }
  }
  val submit = { check: Boolean ->
    // Android 17 blocks devices on the local network until the user allows it.
    access.request(search = false) {
      form.connect(scope, connector, check) { connected ->
        state.reportConnected(connected, tried = check)
        onDismiss()
      }
    }
  }
  // This device announces itself too when it is shared; it is no device to add.
  val local = instances.firstOrNull { it is EmbeddedInstance }
  val sharingPort = (serverState as? ServerState.Running)?.port
  val added = instances.associateBy { it.deviceId }
  AddDeviceDialog(
    form = form,
    device = device,
    nearby = nearby,
    hidden = { it.port == sharingPort && it.name == local?.label },
    added = { server -> added["${server.host}:${server.port}"] != null },
    onSubmit = submit,
    onPick = { server ->
      val known = added["${server.host}:${server.port}"]
      when {
        known != null -> {
          state.switchInstance(known)
          onDismiss()
        }
        server.tokenRequired && server.pairable -> form.pair(server, scope, connector) {
          state.reportConnected(it, tried = true)
          onDismiss()
        }
        else -> {
          form.pick(server)
          if (!server.tokenRequired) submit(true)
        }
      }
    },
    onFind = { access.request(search = true) { nearby?.search() } },
    onShare = if (local != null && state.instanceManager.isLocalServerSupported) {
      {
        onDismiss()
        state.openSettings(SettingsTarget(SettingsTarget.Page.Sharing, LOCAL_DEVICE_ID))
      }
    } else {
      null
    },
    onDismiss = {
      form.cancel()
      onDismiss()
    },
  )
}

/**
 * The [AddDeviceSheet] over [form] and [nearby], without the state it reads.
 *
 * @param hidden whether a device found on the network is left out, such as this one.
 * @param added whether a device found on the network is added already.
 * @param onSubmit tries the form's device, or with `false` adds it untried.
 * @param onPick connects to a device found on the network.
 * @param onFind starts the network search.
 * @param onShare opens this device's pairing code; `null` when it cannot be shared.
 */
@Composable
internal fun AddDeviceDialog(
  form: ConnectForm,
  device: RemoteInstance?,
  nearby: NearbySearch?,
  onSubmit: (check: Boolean) -> Unit,
  onPick: (DiscoveredServer) -> Unit,
  onFind: () -> Unit,
  onShare: (() -> Unit)?,
  onDismiss: () -> Unit,
  hidden: (DiscoveredServer) -> Boolean = { false },
  added: (DiscoveredServer) -> Boolean = { false },
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  AdaptiveModal(
    onDismissRequest = onDismiss,
    dismissible = !form.isEdited && !form.connecting,
    contentSpacing = spacing.s4,
    title = {
      Text(
        if (device == null) {
          stringResource(Res.string.connect_add_title)
        } else {
          stringResource(Res.string.connect_new_code_title, device.label)
        },
      )
    },
    confirmButton = {
      KetchButton(
        text = stringResource(Res.string.action_connect),
        onClick = { onSubmit(true) },
        enabled = form.pairing == null,
        loading = form.connecting,
      )
    },
    dismissButton = {
      KetchButton(
        text = stringResource(Res.string.action_cancel),
        onClick = onDismiss,
        variant = KetchButtonVariant.Secondary,
      )
    },
  ) {
    val pairing = form.pairing
    if (pairing != null) {
      PairingWaitPanel(pairing, onEnterCode = form::enterCodeInstead)
      return@AdaptiveModal
    }
    Text(
      text = when {
        device != null -> stringResource(Res.string.connect_code_no_longer_accepted, device.label)
        isMobilePlatform -> stringResource(Res.string.connect_scan_hint)
        else -> stringResource(Res.string.connect_paste_hint)
      },
      style = type.bodyS,
      color = colors.textSecondary,
    )
    val submit = { onSubmit(true) }
    PairingLinkField(form, onSubmit = submit, autoFocus = device == null)
    AccessCodeField(form, onSubmit = submit)
    ConnectProblemNotice(
      problem = form.problem,
      onAddAnyway = { onSubmit(false) }.takeIf { device == null },
      onAskAgain = onPick,
    )
    if (nearby != null) {
      NearbySection(
        nearby = nearby,
        servers = nearby.servers.filterNot(hidden),
        added = added,
        busy = form.target?.address.takeIf { form.connecting },
        onPick = onPick,
        onFind = onFind,
      )
    }
    ManualDetails(form, onSubmit = submit)
    if (onShare != null && device == null) ShareInstead(onShare)
  }
}

/**
 * Ketch devices found on the network, each connected with one click, and how the search goes.
 *
 * @param busy address of the device being connected to, which shows a spinner.
 */
@Composable
private fun NearbySection(
  nearby: NearbySearch,
  servers: List<DiscoveredServer>,
  added: (DiscoveredServer) -> Boolean,
  busy: String?,
  onPick: (DiscoveredServer) -> Unit,
  onFind: () -> Unit,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  if (!nearby.searched && !nearby.searching) {
    KetchButton(
      text = stringResource(Res.string.connect_find_on_network),
      onClick = onFind,
      variant = KetchButtonVariant.Secondary,
      leadingIcon = KetchIcon.Search,
    )
    return
  }
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier.fillMaxWidth().heightIn(min = KetchTheme.density.buttonSmall),
    ) {
      KetchEyebrow(
        text = stringResource(Res.string.connect_on_your_network),
        modifier = Modifier.weight(1f),
        color = colors.textSecondary,
      )
      when {
        nearby.searching -> {
          KetchSpinner()
          Text(
            text = stringResource(Res.string.connect_searching),
            style = type.caption,
            color = colors.textTertiary,
          )
        }
        nearby.searched -> KetchButton(
          text = stringResource(Res.string.connect_search_again),
          onClick = onFind,
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
          leadingIcon = KetchIcon.Retry,
          modifier = Modifier.offset(x = ghostOverhang()),
        )
      }
    }
    val error = nearby.error
    when {
      servers.isNotEmpty() -> NearbyList(servers, added, busy, onPick)
      else -> Text(
        text = when {
          nearby.searching -> Res.string.connect_looking.text()
          error != null -> Res.string.connect_nearby_failed_hint.text(error)
          else -> Res.string.connect_none_found.text()
        }.resolve(),
        style = type.caption,
        color = if (error != null) colors.status.failed.color else colors.textSecondary,
      )
    }
  }
}

@Composable
private fun NearbyList(
  servers: List<DiscoveredServer>,
  added: (DiscoveredServer) -> Boolean,
  busy: String?,
  onPick: (DiscoveredServer) -> Unit,
) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.md
  Column(Modifier.fillMaxWidth().clip(shape).border(HairlineWidth, colors.hairline, shape)) {
    servers.forEachIndexed { index, server ->
      if (index > 0) {
        Spacer(Modifier.fillMaxWidth().height(HairlineWidth).background(colors.divider))
      }
      val address = PairingLink(server.host, server.port).address
      NearbyRow(
        server = server,
        added = added(server),
        busy = busy == address,
        enabled = busy == null,
        onClick = { onPick(server) },
      )
    }
  }
}

@Composable
private fun NearbyRow(
  server: DiscoveredServer,
  added: Boolean,
  busy: Boolean,
  enabled: Boolean,
  onClick: () -> Unit,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions, enabled)
  val focus = rememberFocusVisibility()
  val address = PairingLink(server.host, server.port).address
  val announced = deviceNameOrNull(server.name)
  val name = announced ?: server.host
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = KetchTheme.density.listRow)
      // Drawn inside the row, since the list clips what reaches past its rounded edge.
      .focusRing(focus.visible, KetchTheme.shapes.sm, colors.focusRing, gap = -spacing.s0_5)
      .background(overlay)
      .ketchClickable(
        interactions = interactions,
        focus = focus,
        enabled = enabled,
        onClickLabel = stringResource(
          if (added) Res.string.action_show else Res.string.action_connect,
        ),
        onClick = onClick,
      )
      .padding(horizontal = spacing.s3, vertical = spacing.s2),
  ) {
    // An address makes no monogram, so a device without a name of its own shows a server.
    DevicePennant(
      deviceId = address,
      name = name,
      fallbackType = DeviceType.Server.takeIf { announced == null },
    )
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
      Text(
        text = name,
        style = type.label,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = when {
          server.tokenRequired && server.pairable ->
            stringResource(Res.string.connect_asks_for_approval, address)
          server.tokenRequired -> stringResource(Res.string.connect_asks_for_code, address)
          else -> address
        },
        style = type.caption,
        color = colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = spacing.s4)) {
      when {
        busy -> KetchSpinner(size = KetchTheme.density.controlGlyph, color = colors.accent)
        added -> Text(
          text = stringResource(Res.string.connect_added_badge),
          style = type.labelS,
          color = colors.textTertiary,
        )
        else -> Chevron()
      }
    }
  }
}

/**
 * What the sheet shows while the owner of a device decides whether to let this one in: the
 * code they check, and the way to type the access code instead.
 */
@Composable
private fun PairingWaitPanel(pairing: PairingWait, onEnterCode: () -> Unit) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s3)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    ) {
      KetchSpinner()
      Text(
        text = stringResource(Res.string.connect_approval_waiting, pairing.name),
        style = type.label,
        color = colors.textPrimary,
      )
    }
    Text(
      text = stringResource(Res.string.connect_approval_check, pairing.name),
      style = type.bodyS,
      color = colors.textSecondary,
    )
    Text(
      text = pairing.code,
      style = type.numeralXL,
      color = colors.textPrimary,
      modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s2),
      textAlign = TextAlign.Center,
    )
    KetchButton(
      text = stringResource(Res.string.connect_approval_enter_code),
      onClick = onEnterCode,
      variant = KetchButtonVariant.Ghost,
      size = KetchButtonSize.Small,
      modifier = Modifier.offset(x = -ghostOverhang()),
    )
  }
}

/** The way to the other direction: this device's own pairing code, for another to scan. */
@Composable
private fun ShareInstead(onShare: () -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val noun = localDeviceNounInSentence().resolve()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      text = stringResource(Res.string.connect_share_instead, noun),
      style = KetchTheme.typography.caption,
      color = colors.textSecondary,
      modifier = Modifier.weight(1f),
    )
    KetchButton(
      text = stringResource(Res.string.connect_show_pairing_code),
      onClick = onShare,
      variant = KetchButtonVariant.Ghost,
      size = KetchButtonSize.Small,
      leadingIcon = KetchIcon.QrCode,
      modifier = Modifier.offset(x = ghostOverhang()),
    )
  }
}

private val HairlineWidth = 1.dp
