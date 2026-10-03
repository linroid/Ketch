package com.linroid.ketch.app.ui.devices

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.pulse.diskUsed
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_show
import ketch.app.shared.generated.resources.device_chip_networks
import ketch.app.shared.generated.resources.device_chip_not_shared
import ketch.app.shared.generated.resources.device_chip_share_tooltip
import ketch.app.shared.generated.resources.device_chip_sharing_settings
import ketch.app.shared.generated.resources.device_count_active
import ketch.app.shared.generated.resources.device_count_downloading_on
import ketch.app.shared.generated.resources.device_count_failed
import ketch.app.shared.generated.resources.device_count_failed_on
import ketch.app.shared.generated.resources.device_count_paused
import ketch.app.shared.generated.resources.device_count_paused_on
import ketch.app.shared.generated.resources.device_count_waiting
import ketch.app.shared.generated.resources.device_count_waiting_on
import ketch.app.shared.generated.resources.device_free_space_unknown
import ketch.app.shared.generated.resources.device_lane_downloads
import ketch.app.shared.generated.resources.device_lane_empty
import ketch.app.shared.generated.resources.device_start_now
import ketch.app.shared.generated.resources.device_storage_short
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * A 6 dp strip of the device's downloads, one block per downloading task sized by what it still
 * needs, in the lane colors with seams between them; an empty track while nothing downloads.
 */
@Composable
internal fun DeviceLane(blocks: List<LaneBlock>, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val shares = remember(blocks) { laneShares(blocks) }
  val left = blocks.mapNotNull { it.remainingBytes }.sum()
  val description = when (blocks.size) {
    0 -> Res.string.device_lane_empty.text()
    else -> Res.plurals.device_lane_downloads.text(blocks.size, blocks.size, sizeText(left))
  }.resolve()
  Spacer(
    modifier
      .fillMaxWidth()
      .height(LaneHeight)
      .clip(KetchTheme.shapes.progressBar)
      .background(colors.surfaceSunken)
      .drawBehind {
        val seam = SeamWidth.toPx()
        var x = 0f
        shares.forEachIndexed { index, share ->
          val width = size.width * share
          val last = index == shares.lastIndex
          drawRect(
            color = colors.lanes[index % colors.lanes.size],
            topLeft = Offset(x, 0f),
            size = Size((width - if (last) 0f else seam).coerceAtLeast(0f), size.height),
          )
          x += width
        }
      }
      .semantics { contentDescription = description }
  )
}

/**
 * The device's tasks on the Downloading, Waiting, Paused and Failed tabs, with dividers between
 * them; each opens that tab on the device in one click.
 */
@Composable
internal fun CountsRow(state: AppState, device: DevicePresence) {
  val counts = device.counts
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min),
  ) {
    val cells = listOf(
      StatusFilter.Downloading to counts.downloading,
      StatusFilter.Waiting to counts.waiting,
      StatusFilter.Paused to counts.paused,
      StatusFilter.Failed to counts.failed,
    )
    cells.forEachIndexed { index, (filter, count) ->
      if (index > 0) CountDivider()
      val cell = CountTexts.getValue(filter)
      CountCell(
        count = count,
        label = stringResource(cell.label),
        alert = filter == StatusFilter.Failed && device.failures > 0,
        description = cell.description.text(count, count, device.name).resolve(),
        onClick = { state.showDeviceTab(device.entry, filter) },
      )
    }
  }
}

@Composable
private fun RowScope.CountCell(
  count: Int,
  label: String,
  alert: Boolean,
  description: String,
  onClick: () -> Unit,
) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.sm
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    modifier = Modifier
      .weight(1f)
      .focusRing(focus.visible, shape, colors.focusRing, gap = -KetchTheme.spacing.s0_5)
      .clip(shape)
      .background(overlay)
      .clearAndSetSemantics { contentDescription = description }
      .trackFocusVisibility(focus)
      .clickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClickLabel = stringResource(Res.string.action_show),
        onClick = onClick,
      )
      .padding(vertical = KetchTheme.spacing.s1),
  ) {
    Text(
      text = count.toString(),
      style = KetchTheme.typography.numeralL,
      color = when {
        alert -> colors.status.failed.color
        count == 0 -> colors.textTertiary
        else -> colors.textPrimary
      },
      maxLines = 1,
    )
    Text(
      text = label,
      style = KetchTheme.typography.caption,
      color = if (alert) colors.status.failed.color else colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

@Composable
private fun CountDivider() {
  Spacer(
    Modifier
      .padding(vertical = KetchTheme.spacing.s2)
      .width(HairlineWidth)
      .fillMaxHeight()
      .background(KetchTheme.colors.divider)
  )
}

/**
 * Free space where the device saves downloads, as a 4 dp bar and "412 GB free of 926 GB", then
 * its download folder. Amber when the downloads still to come need more than is left.
 */
@Composable
internal fun Storage(device: DevicePresence, work: DeviceWork) {
  val colors = KetchTheme.colors
  val disk = device.disk
  val short = disk != null && work.pendingBytes > disk.usableBytes
  val ink = if (short) colors.status.paused.color else colors.textSecondary
  val folder = disk?.directory ?: device.status?.system?.downloadDirectory
  Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2)) {
    Box(
      Modifier
        .fillMaxWidth()
        .height(StorageBarHeight)
        .clip(KetchTheme.shapes.full)
        .background(colors.surfaceSunken)
    ) {
      if (disk != null) {
        Box(
          Modifier
            .fillMaxHeight()
            .fillMaxWidth(diskUsed(disk))
            .background(if (short) colors.status.paused.color else colors.textTertiary)
        )
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = when {
          disk == null -> stringResource(Res.string.device_free_space_unknown)
          short -> listOf(storageLabel(disk), Res.string.device_storage_short.text()).joinText()
            .resolve()
          else -> storageLabel(disk).resolve()
        },
        style = KetchTheme.typography.caption,
        color = ink,
        maxLines = 1,
      )
      if (folder != null) {
        Spacer(Modifier.width(KetchTheme.spacing.s3))
        Text(
          text = shortPathText(folder).resolve(),
          style = KetchTheme.typography.mono,
          color = colors.textTertiary,
          maxLines = 1,
          overflow = TextOverflow.MiddleEllipsis,
          textAlign = TextAlign.End,
          modifier = Modifier.weight(1f),
        )
      }
    }
  }
}

/**
 * A fact chip of a device card, such as the networks it downloads over; [onClick] opens where it
 * is changed.
 */
internal data class DeviceChip(
  val icon: KetchIcon,
  val label: UiText,
  val tooltip: UiText,
  val onClick: () -> Unit,
)

/**
 * The networks [device] downloads over, read once it is online, and for this device whether it
 * is shared on the network.
 */
@Composable
internal fun deviceChips(state: AppState, device: DevicePresence): List<DeviceChip> {
  val settings = state.settingsFor(device.entry)
  val online = device.health.isOnline
  LaunchedEffect(device.entry, online) {
    if (online) settings.loadNetworks()
  }
  val server by state.serverState.collectAsState()
  val networks = settings.networks?.let(::networkNames).orEmpty()
  val local = device.entry is EmbeddedInstance && state.instanceManager.isLocalServerSupported
  return buildList {
    if (networks.isNotEmpty()) {
      add(
        DeviceChip(
          icon = KetchIcon.Network,
          label = verbatim(networks.joinToString(" + ")),
          tooltip = Res.string.device_chip_networks.text(),
          onClick = {
            state.openSettings(SettingsTarget(SettingsTarget.Page.Network, device.deviceId))
          },
        )
      )
    }
    if (local) {
      val sharing = sharingLabel(server)
      add(
        DeviceChip(
          icon = KetchIcon.Server,
          label = sharing ?: Res.string.device_chip_not_shared.text(),
          tooltip = if (sharing != null) {
            Res.string.device_chip_sharing_settings.text()
          } else {
            Res.string.device_chip_share_tooltip.text()
          },
          onClick = { state.pairDevice() },
        )
      )
    }
  }
}

/**
 * [chips] in a row that wraps on narrow cards. Each child reports its full width as its least,
 * so the row's height, which lines up the cards of a grid row, counts the lines it wraps to.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ChipsRow(chips: List<DeviceChip>) {
  val spacing = KetchTheme.spacing
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
  ) {
    chips.forEach { InfoChip(it, Modifier.width(IntrinsicSize.Max)) }
  }
}

@Composable
private fun InfoChip(chip: DeviceChip, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.full
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  KetchTooltip(text = chip.tooltip.resolve(), modifier = modifier) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s1),
      modifier = Modifier
        .focusRing(focus.visible, shape, colors.focusRing)
        .height(KetchTheme.density.chip)
        .clip(shape)
        .background(colors.surfaceSunken)
        .background(overlay)
        .trackFocusVisibility(focus)
        .clickable(
          interactionSource = interactions,
          indication = null,
          role = Role.Button,
          onClick = chip.onClick,
        )
        .padding(start = spacing.s2, end = spacing.s3),
    ) {
      KetchIconImage(
        icon = chip.icon,
        size = KetchTheme.density.controlGlyph,
        tint = colors.textTertiary,
      )
      Text(
        text = chip.label.resolve(),
        style = KetchTheme.typography.labelS,
        color = colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** The buttons of [actions], wrapping onto a second line on narrow cards; see [ChipsRow]. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ActionsRow(actions: List<NextAction>, onAction: (NextAction) -> Unit) {
  val spacing = KetchTheme.spacing
  FlowRow(
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
  ) {
    actions.forEach { action ->
      KetchButton(
        text = action.label.resolve(),
        onClick = { onAction(action) },
        variant = KetchButtonVariant.Secondary,
        size = KetchButtonSize.Small,
        leadingIcon = when (action) {
          is NextAction.RetryFailed -> KetchIcon.Retry
          NextAction.PauseAll -> KetchIcon.Pause
          is NextAction.StartNow -> KetchIcon.Play
        },
        tooltip = (action as? NextAction.StartNow)?.let {
          stringResource(Res.string.device_start_now, it.name)
        },
        modifier = Modifier.width(IntrinsicSize.Max),
      )
    }
  }
}

/** What a cell of [CountsRow] says under its count, and to a screen reader. */
private class CountText(val label: StringResource, val description: PluralStringResource)

private val CountTexts = mapOf(
  StatusFilter.Downloading to
    CountText(Res.string.device_count_active, Res.plurals.device_count_downloading_on),
  StatusFilter.Waiting to
    CountText(Res.string.device_count_waiting, Res.plurals.device_count_waiting_on),
  StatusFilter.Paused to
    CountText(Res.string.device_count_paused, Res.plurals.device_count_paused_on),
  StatusFilter.Failed to
    CountText(Res.string.device_count_failed, Res.plurals.device_count_failed_on),
)

private val LaneHeight: Dp = 6.dp
private val SeamWidth: Dp = 1.dp
private val HairlineWidth: Dp = 1.dp
private val StorageBarHeight: Dp = 4.dp
