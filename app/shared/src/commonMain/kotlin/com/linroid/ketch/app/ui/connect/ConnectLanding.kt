package com.linroid.ketch.app.ui.connect

import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.constrainWidth
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchEyebrow
import com.linroid.ketch.app.components.SailLanesIllustration
import com.linroid.ketch.app.components.SailLanesIllustrationDefaults
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.healthColor
import com.linroid.ketch.app.components.ketchClickable
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.feedback.ToastHost
import com.linroid.ketch.app.ui.shell.canvasWash

/**
 * The page shown in place of the app while no device is shown, as in the web app before one is
 * connected: a pairing link or address connects in one step, the access code field appears
 * once a device asks for one, and devices added before are a click away; one whose code no
 * longer works asks for the current one here. It never opens a dialog by itself.
 */
@Composable
fun ConnectLanding(state: AppState, modifier: Modifier = Modifier) {
  val form = remember { ConnectForm() }
  val connector = rememberDeviceConnector(state)
  val scope = rememberCoroutineScope()
  val devices by state.instanceManager.presence.collectAsState()
  Box(modifier.fillMaxSize()) {
    ConnectLandingContent(
      form = form,
      devices = devices,
      onSubmit = { check ->
        form.connect(scope, connector, check) { device ->
          state.reportConnected(device, check)
          // Added untried, it waits under Your devices, and the field is free for the next.
          if (!check) form.link = ""
        }
      },
      onPick = { device ->
        val remote = device.entry as? RemoteInstance
        if (remote != null && device.needsCode) {
          form.askForCode(remote)
        } else {
          state.switchInstance(device.entry)
        }
      },
    )
    ToastHost(
      messages = state.messages,
      modifier = Modifier
        .align(Alignment.BottomCenter)
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .padding(KetchTheme.spacing.s4),
    )
  }
}

/**
 * The [ConnectLanding] over [form], without the state it reads.
 *
 * @param devices devices added before; each shows how it is reached and opens with a click.
 * @param onSubmit tries the form's device, or with `false` adds it untried.
 */
@Composable
internal fun ConnectLandingContent(
  form: ConnectForm,
  devices: List<DevicePresence>,
  onSubmit: (check: Boolean) -> Unit,
  onPick: (DevicePresence) -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  val submit = { onSubmit(true) }
  BoxWithConstraints(
    modifier
      .fillMaxSize()
      .canvasWash(colors, EmberRadius)
      .windowInsetsPadding(WindowInsets.safeDrawing),
  ) {
    val narrow = maxWidth < CardWidth + spacing.s4 * 2
    Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.Center,
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = maxHeight)
        .verticalScroll(rememberScrollState())
        .padding(horizontal = spacing.s4, vertical = if (narrow) spacing.s4 else spacing.s10),
    ) {
      Column(
        verticalArrangement = Arrangement.spacedBy(spacing.s4),
        modifier = Modifier
          .widthIn(max = CardWidth)
          .fillMaxWidth()
          .ketchSurface(
            level = KetchElevationLevel.E2,
            shape = KetchTheme.shapes.lg,
            fill = colors.surface,
            border = colors.hairline,
          )
          .padding(if (narrow) spacing.s5 else spacing.s6),
      ) {
        Column(
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(spacing.s2),
          modifier = Modifier.fillMaxWidth(),
        ) {
          SailLanesIllustration(width = SailLanesIllustrationDefaults.CompactWidth)
          Text(
            text = "Connect to a Ketch device",
            style = type.titleL,
            color = colors.textPrimary,
            textAlign = TextAlign.Center,
          )
          Text(
            text = "Control the downloads of a computer, NAS or phone that runs Ketch.",
            style = type.bodyS,
            color = colors.textSecondary,
            textAlign = TextAlign.Center,
          )
        }
        PairingLinkField(
          form = form,
          onSubmit = submit,
          // The whole hint does not fit a phone's card.
          placeholder = if (narrow) NarrowPlaceholder else DefaultPlaceholder,
          autoFocus = true,
        )
        AccessCodeField(form, onSubmit = submit)
        ManualDetails(form, onSubmit = submit, label = "Enter address manually")
        ConnectProblemNotice(form.problem, onAddAnyway = { onSubmit(false) })
        KetchButton(
          text = "Connect",
          onClick = submit,
          loading = form.connecting,
          modifier = Modifier.align(Alignment.End),
        )
        if (devices.isNotEmpty()) {
          Divider(colors)
          LandingSection("Your devices") {
            // The rows' hover reaches past the text, which lines up with the rest of the card.
            Column(Modifier.fillMaxWidth().bleed(spacing.s2)) {
              devices.forEach { device -> LandingDeviceRow(device, onClick = { onPick(device) }) }
            }
          }
        }
        Divider(colors)
        LandingSection("Where to find the link") {
          Text(
            text = buildAnnotatedString {
              append("On the computer that runs Ketch, open Settings › Sharing › Allow another ")
              append("device, then copy the pairing link. On a NAS or server, run ")
              withStyle(SpanStyle(fontFamily = type.mono.fontFamily, color = colors.textPrimary)) {
                append("ketch\u00A0server")
              }
              append(".")
            },
            style = type.bodyS,
            color = colors.textSecondary,
          )
        }
      }
    }
  }
}

@Composable
private fun LandingSection(title: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2)) {
    KetchEyebrow(title, color = KetchTheme.colors.textSecondary)
    content()
  }
}

@Composable
private fun Divider(colors: KetchColors) {
  Spacer(Modifier.fillMaxWidth().height(HairlineWidth).background(colors.divider))
}

/** A device added before, with its pennant, address and whether it can be reached now. */
@Composable
private fun LandingDeviceRow(device: DevicePresence, onClick: () -> Unit) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.sm
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  val (label, tone) = landingStatus(device, colors)
  // A device without a name of its own goes by its address, which makes no monogram.
  val unnamed = (device.entry as? RemoteInstance)?.let { it.remoteConfig.name == null } == true
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = KetchTheme.density.listRow - spacing.s2)
      .focusRing(focus.visible, shape, colors.focusRing)
      .clip(shape)
      .background(overlay)
      .ketchClickable(
        interactions = interactions,
        focus = focus,
        onClickLabel = if (device.needsCode) "Enter its access code" else "Show",
        onClick = onClick,
      )
      .padding(horizontal = spacing.s2),
  ) {
    // Sized for the ring, so names line up whether or not a device has one.
    Box(Modifier.size(DevicePennantDefaults.Large), contentAlignment = Alignment.Center) {
      DevicePennant(
        deviceId = device.deviceId,
        name = device.name,
        health = device.health.takeIf { device.connected },
        failures = device.unseenFailures,
        icon = KetchIcon.Server.takeIf { unnamed },
      )
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
      Text(
        text = device.name,
        style = type.label,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (!unnamed) {
        Text(
          text = device.detail,
          style = type.caption,
          color = colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Text(text = label, style = type.labelS, color = tone, maxLines = 1)
  }
}

/** What a device row says about how [device] is reached, and in which color. */
private fun landingStatus(device: DevicePresence, colors: KetchColors) = when {
  !device.connected -> "Not connected" to colors.textTertiary
  device.needsCode -> "Needs a code" to colors.status.failed.color
  device.health is DeviceHealth.Offline -> "Offline" to colors.status.failed.color
  device.health == DeviceHealth.Connecting -> "Connecting…" to colors.textSecondary
  else -> "Online" to colors.healthColor(device.health)
}

// Whether the device turned down the access code it was given.
private val DevicePresence.needsCode: Boolean
  get() = connected && health == DeviceHealth.Unauthorized

/** Widens this element by [horizontal] on each side, past its parent's padding. */
private fun Modifier.bleed(horizontal: Dp): Modifier = layout { measurable, constraints ->
  val extra = if (constraints.hasBoundedWidth) horizontal.roundToPx() else 0
  val placeable = measurable.measure(
    constraints.copy(
      minWidth = constraints.minWidth + extra * 2,
      maxWidth = constraints.maxWidth + extra * 2,
    ),
  )
  layout(constraints.constrainWidth(placeable.width - extra * 2), placeable.height) {
    placeable.place(-extra, 0)
  }
}

private const val NarrowPlaceholder = "nas.local:8642"
private val CardWidth = 480.dp
private val HairlineWidth = 1.dp

// Radius of the wash's ember glow, as behind the shell.
private val EmberRadius = 520.dp
