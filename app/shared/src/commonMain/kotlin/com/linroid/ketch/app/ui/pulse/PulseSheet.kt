package com.linroid.ketch.app.ui.pulse

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchSpeedChart
import com.linroid.ketch.app.components.SpeedBand
import com.linroid.ketch.app.components.SpeedLimitLine
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.PulseState
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.formatSpace
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.eyebrowText

/**
 * The phone's summary line under the "Downloads" title, such as
 * "↓ 4.2 MB/s · 2 active · Full speed ▾"; tapping it opens the [PulseSheet].
 */
@Composable
fun PulseSubtitle(state: AppState, onClick: () -> Unit, modifier: Modifier = Modifier) {
  val pulse by state.pulse.state.collectAsState()
  val view = rememberSpeedModeView(state)
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.sm
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  val text = pulseSubtitle(pulse, view.label)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
    modifier = modifier
      .focusRing(focus.visible, shape, colors.focusRing)
      .clip(shape)
      .background(overlay)
      .semantics(mergeDescendants = true) { contentDescription = "$text, show the speed" }
      .trackFocusVisibility(focus)
      .clickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClick = onClick,
      ),
  ) {
    Text(
      text = text,
      style = KetchTheme.typography.caption,
      color = if (view.limited) colors.status.paused.color else colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f, fill = false),
    )
    KetchIconImage(icon = KetchIcon.ChevronDown, size = ChevronSize, tint = colors.textTertiary)
  }
}

/** The phone subtitle: "↓ 4.2 MB/s · 2 active · Full speed", or "Idle · Full speed". */
internal fun pulseSubtitle(pulse: PulseState, modeLabel: String): String {
  val device = pulse.devices.firstOrNull()
  val parts = when {
    device != null && !device.health.isOnline -> listOf(healthLabel(device.health))
    pulse.counts.downloading > 0 -> listOf(
      "↓ ${speedText(pulse.totalSpeed)}",
      "${pulse.counts.downloading} active"
    )
    else -> listOf("Idle")
  }
  return (parts + modeLabel).joinToString(SEPARATOR)
}

/**
 * The phone's Pulse sheet: the active device's speed in large numerals, its last minute as a
 * chart with the speed limit, the speed mode and limit, the counts (each opens its tab) and the
 * free space.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PulseSheet(state: AppState, onDismissRequest: () -> Unit) {
  val colors = KetchTheme.colors
  val sheetState = rememberBottomSheetState(
    initialValue = SheetValue.Hidden,
    enabledValues = setOf(SheetValue.Hidden, SheetValue.Expanded),
  )
  ModalBottomSheet(
    onDismissRequest = onDismissRequest,
    sheetState = sheetState,
    shape = KetchTheme.shapes.sheetTop,
    containerColor = colors.surfaceRaised,
    contentColor = colors.textPrimary,
    tonalElevation = 0.dp,
    scrimColor = colors.scrim,
  ) {
    val pulse by state.pulse.state.collectAsState()
    Column(
      modifier = Modifier
        .fillMaxWidth()
        .padding(horizontal = KetchTheme.density.pagePadding)
        .padding(bottom = KetchTheme.spacing.s6),
    ) {
      PulseSummary(
        pulse = pulse,
        limit = rememberSpeedModeView(state).limit.takeUnless { it.isUnlimited }?.bytesPerSecond,
        onShowTab = {
          onDismissRequest()
          state.showDownloads(it)
        },
      )
      Spacer(Modifier.height(KetchTheme.spacing.s6))
      SpeedModeOptions(state, onOpenSettings = onDismissRequest, fillModes = true)
    }
  }
}

/** The top of the [PulseSheet]: speed, chart, counts and disk, drawn from [pulse]. */
@Composable
internal fun PulseSummary(pulse: PulseState, limit: Long?, onShowTab: (StatusFilter) -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val device = pulse.devices.firstOrNull()
  val online = device?.health?.isOnline ?: true
  val speed = speedText(pulse.totalSpeed)
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s3)) {
    Row(verticalAlignment = Alignment.Bottom) {
      Text(
        text = if (online) speed.amount else "—",
        style = KetchTheme.typography.numeralXL,
        color = colors.textPrimary,
      )
      if (online) {
        Text(
          text = " ${speed.unit}",
          style = KetchTheme.typography.body,
          color = colors.textTertiary,
          modifier = Modifier.padding(bottom = spacing.s1),
        )
      }
      Spacer(Modifier.weight(1f))
      if (device != null) {
        Text(
          text = "${device.name} · ${healthLabel(device.health)}",
          style = KetchTheme.typography.caption,
          color = colors.textSecondary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier = Modifier.padding(bottom = spacing.s1, start = spacing.s2),
        )
      }
    }
    Text(
      text = eyebrowText("Last minute"),
      style = KetchTheme.typography.eyebrow,
      color = colors.textTertiary,
    )
    KetchSpeedChart(
      bands = listOf(SpeedBand(pulse.history, colors.accent)),
      limits = listOfNotNull(limit?.let { SpeedLimitLine(it) }),
      slots = CHART_SLOTS,
      modifier = Modifier.fillMaxWidth().height(ChartHeight),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
      CountTile("Downloading", pulse.counts.downloading, StatusFilter.Downloading, onShowTab)
      CountTile("Waiting", pulse.counts.waiting, StatusFilter.Waiting, onShowTab)
      CountTile(
        label = "Failed",
        count = pulse.counts.failed,
        filter = StatusFilter.Failed,
        onShowTab = onShowTab,
        alert = pulse.failures > 0,
      )
    }
    val disk = pulse.diskDevice?.disk
    if (disk != null) {
      Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
        Text(
          text = "${diskLabel(disk)} of ${formatSpace(disk.totalBytes)}",
          style = KetchTheme.typography.caption,
          color = if (pulse.isDiskShort) colors.status.paused.color else colors.textSecondary,
        )
        Box(
          Modifier
            .fillMaxWidth()
            .height(DiskBarHeight)
            .clip(KetchTheme.shapes.full)
            .background(colors.surfaceSunken)
        ) {
          Box(
            Modifier
              .fillMaxHeight()
              .fillMaxWidth(diskUsed(disk))
              .background(
                if (pulse.isDiskShort) colors.status.paused.color else colors.textTertiary
              )
          )
        }
      }
    }
  }
}

@Composable
private fun RowScope.CountTile(
  label: String,
  count: Int,
  filter: StatusFilter,
  onShowTab: (StatusFilter) -> Unit,
  alert: Boolean = false,
) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.md
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  Column(
    modifier = Modifier
      .weight(1f)
      .heightIn(min = TileHeight)
      .focusRing(focus.visible, shape, colors.focusRing)
      .clip(shape)
      .background(colors.surfaceSunken)
      .background(overlay)
      .trackFocusVisibility(focus)
      .clickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClick = { onShowTab(filter) },
      )
      .padding(horizontal = KetchTheme.spacing.s3, vertical = KetchTheme.spacing.s2),
  ) {
    Text(
      text = count.toString(),
      style = KetchTheme.typography.numeralL,
      color = if (alert && count > 0) colors.status.failed.color else colors.textPrimary,
    )
    Text(text = label, style = KetchTheme.typography.caption, color = colors.textSecondary)
  }
}

private const val CHART_SLOTS = 60
private val ChartHeight = 96.dp
private val DiskBarHeight = 4.dp
private val TileHeight = 56.dp
private val ChevronSize = 12.dp
