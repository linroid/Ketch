package com.linroid.ketch.app.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.size
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
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.DiscoveredServer
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.ServerState
import com.linroid.ketch.app.instance.deviceNameOrNull
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText
import com.linroid.ketch.app.ui.common.AdaptiveModal
import com.linroid.ketch.app.util.PairingLink

/**
 * The Add device sheet: connects this app to another Ketch device from its pairing link or
 * address, a device found on the network, or details typed by hand. Each is tried before it is
 * added, so a typo or a missing access code shows in the sheet rather than as an offline
 * device; the access code field appears once a device asks for one.
 *
 * Desktop apps search the network as the sheet opens; phones search when "Find on network" is
 * tapped, which asks Android for its nearby devices permission first. The browser cannot
 * search, so the web hides it.
 *
 * @param device a device that rejected its access code; the sheet then asks for a new one.
 */
@Composable
fun AddDeviceSheet(state: AppState, onDismiss: () -> Unit, device: RemoteInstance? = null) {
  val form = remember(device) { device?.let(::codeForm) ?: ConnectForm() }
  val connector = rememberDeviceConnector(state)
  val scope = rememberCoroutineScope()
  val nearby = rememberNearbySearch().takeIf { it.supported && device == null }
  val access = rememberNearbyAccess()
  val instances by state.instances.collectAsState()
  val serverState by state.serverState.collectAsState()
  LaunchedEffect(nearby) {
    // Asking for a permission is up to the user, so phones wait for the button.
    if (nearby != null && !isMobilePlatform) nearby.search()
  }
  val submit = { check: Boolean ->
    form.connect(scope, connector, check) { connected ->
      state.reportConnected(connected, tried = check)
      onDismiss()
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
        else -> {
          form.pick(server)
          if (!server.tokenRequired) submit(true)
        }
      }
    },
    onFind = { access.request { nearby?.search() } },
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
      Text(if (device == null) "Add a device" else "New access code for ${device.label}")
    },
    confirmButton = {
      KetchButton(text = "Connect", onClick = { onSubmit(true) }, loading = form.connecting)
    },
    dismissButton = {
      KetchButton(text = "Cancel", onClick = onDismiss, variant = KetchButtonVariant.Secondary)
    },
  ) {
    Text(
      text = when {
        device != null -> "${device.label} no longer accepts the code Ketch has. Paste a new " +
          "pairing link from Settings › Sharing on that device, or enter its access code."
        isMobilePlatform -> "Scan the pairing code in Settings › Sharing on the other device " +
          "with your camera app, or paste its pairing link."
        else -> "Paste the pairing link from Settings › Sharing on the other device, or type " +
          "its address."
      },
      style = type.bodyS,
      color = colors.textSecondary,
    )
    val submit = { onSubmit(true) }
    PairingLinkField(form, onSubmit = submit, autoFocus = device == null)
    AccessCodeField(form, onSubmit = submit)
    ConnectProblemNotice(form.problem, onAddAnyway = { onSubmit(false) }.takeIf { device == null })
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
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier.fillMaxWidth().heightIn(min = KetchTheme.density.buttonSmall),
    ) {
      Text(
        text = eyebrowText("On your network"),
        style = type.eyebrow,
        color = colors.textSecondary,
        modifier = Modifier.weight(1f),
      )
      when {
        nearby.searching -> {
          KetchSpinner()
          Text("Searching…", style = type.caption, color = colors.textTertiary)
        }
        nearby.searched -> KetchButton(
          text = "Search again",
          onClick = onFind,
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
          leadingIcon = KetchIcon.Retry,
          modifier = Modifier.offset(x = ghostOverhang()),
        )
      }
    }
    when {
      servers.isNotEmpty() -> NearbyList(servers, added, busy, onPick)
      !nearby.searched && !nearby.searching -> KetchButton(
        text = "Find on network",
        onClick = onFind,
        variant = KetchButtonVariant.Secondary,
        leadingIcon = KetchIcon.Search,
      )
      else -> Text(
        text = when {
          nearby.searching -> "Looking for Ketch devices on this network…"
          nearby.error != null -> "${nearby.error}. Paste a pairing link instead."
          else -> "No devices found. On the other device, open Settings › Sharing and allow " +
            "another device."
        },
        style = type.caption,
        color = if (nearby.error != null) colors.status.failed.color else colors.textSecondary,
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
      .trackFocusVisibility(focus)
      .clickable(
        interactionSource = interactions,
        indication = null,
        enabled = enabled,
        role = Role.Button,
        onClickLabel = if (added) "Show" else "Connect",
        onClick = onClick,
      )
      .padding(horizontal = spacing.s3, vertical = spacing.s2),
  ) {
    // An address makes no monogram, so a device without a name of its own shows a server.
    DevicePennant(
      deviceId = address,
      name = name,
      icon = KetchIcon.Server.takeIf { announced == null },
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
        text = if (server.tokenRequired) "$address · asks for a code" else address,
        style = type.caption,
        color = colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    Box(contentAlignment = Alignment.Center, modifier = Modifier.heightIn(min = spacing.s4)) {
      when {
        busy -> KetchSpinner(size = KetchTheme.density.controlGlyph, color = colors.accent)
        added -> Text("Added", style = type.labelS, color = colors.textTertiary)
        else -> KetchIconImage(
          icon = KetchIcon.Chevron,
          size = KetchTheme.density.controlGlyph,
          tint = colors.textTertiary,
          modifier = Modifier.size(KetchTheme.density.controlGlyph),
        )
      }
    }
  }
}

/** The way to the other direction: this device's own pairing code, for another to scan. */
@Composable
private fun ShareInstead(onShare: () -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val noun = localDeviceNoun().replaceFirstChar { it.lowercase() }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      text = "Want to control $noun from another device?",
      style = KetchTheme.typography.caption,
      color = colors.textSecondary,
      modifier = Modifier.weight(1f),
    )
    KetchButton(
      text = "Show its pairing code",
      onClick = onShare,
      variant = KetchButtonVariant.Ghost,
      size = KetchButtonSize.Small,
      leadingIcon = KetchIcon.QrCode,
      modifier = Modifier.offset(x = ghostOverhang()),
    )
  }
}

private val HairlineWidth = 1.dp
