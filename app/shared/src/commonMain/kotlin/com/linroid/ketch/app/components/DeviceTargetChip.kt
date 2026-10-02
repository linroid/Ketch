package com.linroid.ketch.app.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.theme.KetchTheme

/**
 * A device downloads can be sent to, as a [DeviceTargetChip] lists it.
 *
 * @property id the device's id, as in `TaskKey.deviceId`.
 * @property name name shown for it, such as "This Mac" or "NAS-Basement".
 * @property health how well the app is connected to it; only devices that are online can be
 *   picked.
 * @property pennantName what its pennant's monogram is made from; the host name for the
 *   embedded device.
 * @property summary what goes on there, such as "1.8 TB free · 2 active · Slow lane".
 * @property shortcut chord that picks it while the sheet is open, such as "⌘⌥2".
 */
@Immutable
data class DeviceOption(
  val id: String,
  val name: String,
  val health: DeviceHealth,
  val pennantName: String = name,
  val summary: String? = null,
  val shortcut: String? = null,
)

/**
 * The "On: (LM) This Mac ▾" pill that picks which device downloads go to, in the add sheet and
 * on Discover results. It opens a menu of [options] with each device's summary and shortcut;
 * devices that are offline are listed but cannot be picked. Callers show it only when there are
 * two or more devices.
 *
 * @param selectedId id of the device downloads go to now.
 */
@Composable
fun DeviceTargetChip(
  selectedId: String,
  options: List<DeviceOption>,
  onSelect: (DeviceOption) -> Unit,
  modifier: Modifier = Modifier,
) {
  val selected = options.firstOrNull { it.id == selectedId } ?: options.firstOrNull() ?: return
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.full
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  var expanded by remember { mutableStateOf(false) }
  Box(modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s1),
      modifier = Modifier
        .focusRing(focus.visible, shape, colors.focusRing)
        .height(KetchTheme.density.chip)
        .clip(shape)
        .background(colors.surface)
        .background(overlay)
        .border(1.dp, colors.borderStrong, shape)
        .trackFocusVisibility(focus)
        .clickable(
          interactionSource = interactions,
          indication = null,
          role = Role.DropdownList,
          onClickLabel = "Choose device",
          onClick = { expanded = true },
        )
        .padding(start = spacing.s2, end = spacing.s2),
    ) {
      Text(
        text = "On:",
        style = KetchTheme.typography.labelS,
        color = colors.textTertiary,
        maxLines = 1,
      )
      DevicePennant(
        deviceId = selected.id,
        name = selected.pennantName,
        size = DevicePennantDefaults.XSmall,
      )
      Text(
        text = selected.name,
        style = KetchTheme.typography.labelS,
        fontWeight = FontWeight.SemiBold,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      KetchIconImage(KetchIcon.ChevronDown, size = ChevronSize, tint = colors.textSecondary)
    }
    KetchMenu(expanded = expanded, onDismissRequest = { expanded = false }, title = MENU_TITLE) {
      for (option in options) {
        item(
          label = option.name,
          onClick = { onSelect(option) },
          shortcut = option.shortcut,
          caption = deviceOptionCaption(option),
          enabled = option.health.isOnline,
          checked = option.id == selected.id,
        )
      }
    }
  }
}

/** The line under a device in the target menu: why it cannot be picked, or its [summary]. */
internal fun deviceOptionCaption(option: DeviceOption): String? = when (option.health) {
  DeviceHealth.Connecting -> "Connecting…"
  is DeviceHealth.Offline -> "Offline"
  DeviceHealth.Unauthorized -> "Needs a new access token"
  is DeviceHealth.Local, DeviceHealth.Live -> option.summary
}

private const val MENU_TITLE = "Download on"
private val ChevronSize = 12.dp
