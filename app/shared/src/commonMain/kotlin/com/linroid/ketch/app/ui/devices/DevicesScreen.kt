package com.linroid.ketch.app.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.shell.LocalKetchLayout
import com.linroid.ketch.app.ui.shell.ShellNavigation
import com.linroid.ketch.app.ui.sidebar.deviceLine
import com.linroid.ketch.app.ui.sidebar.pennantHealth
import com.linroid.ketch.app.ui.sidebar.pennantName
import com.linroid.ketch.app.ui.sidebar.rememberDevices

/**
 * The Devices page: every device the app knows with what it is doing, which one the app shows,
 * and a way to add another. Choosing a device switches the app to it.
 */
@Composable
fun DevicesScreen(state: AppState) {
  val devices = rememberDevices(state)
  val active by state.activeInstance.collectAsState()
  val spacing = KetchTheme.spacing
  val colors = KetchTheme.colors
  val padding = KetchTheme.density.pagePadding
  // Phones name the page in their top bar already.
  val phone = LocalKetchLayout.current.navigation == ShellNavigation.Phone
  Column(Modifier.fillMaxSize()) {
    if (!phone) {
      Box(
        contentAlignment = Alignment.CenterStart,
        modifier = Modifier
          .fillMaxWidth()
          .height(spacing.pageHeaderHeight)
          .padding(horizontal = padding),
      ) {
        Text(text = "Devices", style = KetchTheme.typography.pageTitle, color = colors.textPrimary)
      }
    }
    Column(
      verticalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier
        .weight(1f)
        .fillMaxWidth()
        .verticalScroll(rememberScrollState())
        .padding(horizontal = padding)
        .padding(top = if (phone) padding else spacing.s2, bottom = padding)
        .widthIn(max = PageMaxWidth)
        .fillMaxWidth(),
    ) {
      val shape = KetchTheme.shapes.lg
      Column(
        modifier = Modifier
          .fillMaxWidth()
          .clip(shape)
          .background(colors.surface)
          .border(HairlineWidth, colors.hairline, shape),
      ) {
        devices.forEach { device ->
          key(device.deviceId) {
            DeviceItem(
              device = device,
              active = device.deviceId == active?.deviceId,
              onClick = { state.switchInstance(device.entry) },
            )
            Divider()
          }
        }
        AddDeviceItem(onClick = { state.showAddRemoteDialog = true })
      }
      Text(
        text = "Choose a device to see and manage its downloads.",
        style = KetchTheme.typography.caption,
        color = colors.textSecondary,
        modifier = Modifier.padding(horizontal = spacing.s1),
      )
    }
  }
}

/** One device: its pennant, name and host, what it is doing, and a check when it shows. */
@Composable
private fun DeviceItem(device: DevicePresence, active: Boolean, onClick: () -> Unit) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val line = deviceLine(device)
  ItemRow(selected = active, onClick = onClick) {
    Box(Modifier.size(PennantRoom), contentAlignment = Alignment.Center) {
      DevicePennant(
        deviceId = device.deviceId,
        name = device.pennantName,
        size = DevicePennantDefaults.Large,
        health = pennantHealth(device),
        failures = device.unseenFailures,
      )
    }
    Column(Modifier.weight(1f)) {
      Text(
        text = device.name,
        style = type.bodyStrong,
        fontWeight = if (active) FontWeight.SemiBold else null,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      if (device.detail.isNotBlank() && device.detail != device.name) {
        Text(
          text = device.detail,
          style = type.caption,
          color = colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    Text(
      text = line.text,
      style = type.numeralS,
      color = if (line.alert) colors.status.failed.color else colors.textSecondary,
      maxLines = 1,
    )
    Box(Modifier.size(KetchTheme.density.controlGlyph), contentAlignment = Alignment.Center) {
      if (active) {
        KetchIconImage(
          icon = KetchIcon.Check,
          size = KetchTheme.density.controlGlyph,
          tint = colors.accentText,
        )
      }
    }
  }
}

/** The last row, which asks for a device to connect to. */
@Composable
private fun AddDeviceItem(onClick: () -> Unit) {
  val colors = KetchTheme.colors
  ItemRow(selected = false, onClick = onClick) {
    Box(Modifier.size(PennantRoom), contentAlignment = Alignment.Center) {
      KetchIconImage(
        icon = KetchIcon.Plus,
        size = KetchTheme.density.controlGlyph,
        tint = colors.accentText,
      )
    }
    Text(
      text = "Add device",
      style = KetchTheme.typography.label,
      color = colors.accentText,
      modifier = Modifier.weight(1f),
    )
  }
}

@Composable
private fun ItemRow(
  selected: Boolean,
  onClick: () -> Unit,
  content: @Composable RowScope.() -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = ItemHeight)
      .focusRing(focus.visible, KetchTheme.shapes.md, colors.focusRing, gap = -spacing.s0_5)
      .background(if (selected) colors.rowSelected else colors.surface)
      .background(overlay)
      .trackFocusVisibility(focus)
      .selectable(
        selected = selected,
        interactionSource = interactions,
        indication = null,
        role = Role.RadioButton,
        onClick = onClick,
      )
      .padding(horizontal = spacing.s4, vertical = spacing.s2),
    content = content,
  )
}

@Composable
private fun Divider() {
  Spacer(
    Modifier
      .fillMaxWidth()
      .padding(start = DividerInset)
      .height(HairlineWidth)
      .background(KetchTheme.colors.divider),
  )
}

private val ItemHeight: Dp = 56.dp
private val HairlineWidth: Dp = 1.dp
private val PageMaxWidth: Dp = 720.dp

// A 32 dp pennant with its health ring.
private val PennantRoom: Dp = 40.dp

// The row's padding, the pennant's room and the gap after it, so dividers start under the names.
private val DividerInset: Dp = 68.dp
