package com.linroid.ketch.app.ui.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.DevicePennant
import com.linroid.ketch.app.components.DevicePennantDefaults
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.components.KetchFileTypeChip
import com.linroid.ketch.app.components.KetchFileTypeChipDefaults
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchSpeedChart
import com.linroid.ketch.app.components.SpeedBand
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.DeviceScope
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DevicePulse
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.state.toDeviceHealth
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.StackedPennants
import com.linroid.ketch.app.ui.inspector.tabs.formatSize
import com.linroid.ketch.app.ui.inspector.tabs.formatSpeed
import com.linroid.ketch.app.ui.inspector.tabs.plural
import com.linroid.ketch.app.ui.pulse.totalHistory

/**
 * What the inspector shows with no download selected: the active device's speed over the last
 * minute, connections in flight, slots and per-site limits in use, free space, the networks it
 * downloads over, and Up next, the first waiting downloads, each with Start now. While every
 * device shows, it sums them up instead (see [FleetOverview]).
 */
@Composable
internal fun ScopeOverview(state: AppState, onClose: () -> Unit) {
  val scope by state.deviceScope.collectAsState()
  if (scope == DeviceScope.All) FleetOverview(state, onClose) else DeviceOverview(state, onClose)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun DeviceOverview(state: AppState, onClose: () -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val active by state.activeInstance.collectAsState()
  val rows by state.taskList.rows.collectAsState()
  val pulse by state.pulse.state.collectAsState()
  val histories by state.speedHistory.histories.collectAsState()
  val settings = state.instanceSettings
  val deviceId = active?.deviceId ?: LOCAL_DEVICE_ID
  val device = pulse.devices.firstOrNull { it.deviceId == deviceId }
  val mine = remember(rows, deviceId) { rows.filter { it.key.deviceId == deviceId } }
  val summary = remember(mine, settings.download) { scopeSummary(mine, settings.download) }
  LaunchedEffect(settings) { if (settings.networks == null) settings.loadNetworks() }

  Column(verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.s3)) {
      val entry = active
      DevicePennant(
        deviceId = deviceId,
        name = entry?.label ?: "",
        size = DevicePennantDefaults.Large,
        health = when (entry) {
          is RemoteInstance -> entry.connectionState.collectAsState().value.toDeviceHealth()
          is EmbeddedInstance -> DeviceHealth.Local()
          else -> null
        },
      )
      Column(
        verticalArrangement = Arrangement.spacedBy(spacing.s0_5),
        modifier = Modifier.weight(1f),
      ) {
        Text(
          text = entry?.displayName ?: "No device",
          style = type.titleM,
          color = colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = keepPartsTogether(pulse.sentence(now = LocalClock.current.now())),
          style = type.caption,
          color = colors.textSecondary,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      KetchIconButton(
        icon = KetchIcon.Close,
        contentDescription = "Close inspector",
        onClick = onClose,
        size = KetchButtonSize.Small,
      )
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
      Row(verticalAlignment = Alignment.Bottom) {
        Text(
          text = formatSpeed(device?.speed ?: 0),
          style = type.numeralL,
          color = colors.textPrimary,
          modifier = Modifier.weight(1f),
        )
        val cap = device?.cap
        if (cap != null && !cap.isUnlimited) {
          Text(
            text = "Limit ${formatSpeedLimit(cap)}",
            style = type.caption,
            color = colors.textTertiary,
            modifier = Modifier.padding(bottom = spacing.s0_5),
          )
        }
      }
      val lanes = colors.lanes
      val bands = remember(histories, mine, lanes) {
        mine.filter { it.state is DownloadState.Downloading }
          .mapNotNull { histories[it.key] }
          .mapIndexed { index, history ->
            SpeedBand(history.toList().takeLast(CHART_SECONDS), lanes[index % lanes.size])
          }
      }
      KetchSpeedChart(
        bands = bands,
        slots = CHART_SECONDS,
        modifier = Modifier.fillMaxWidth().height(spacing.s16),
      )
      Row {
        Text(
          text = "1 min ago",
          style = type.numeralS,
          color = colors.textTertiary,
          modifier = Modifier.weight(1f),
        )
        Text(text = "Now", style = type.numeralS, color = colors.textTertiary)
      }
    }

    InspectorSection("In flight") {
      Stat("Connections", connectionsText(summary))
      val slots = summary.slots
      if (slots != null) Stat("Slots", "${summary.running} of $slots in use")
      for (host in summary.hosts) Stat(host.host, "${host.running} of ${host.limit}")
      val disk = device?.disk
      if (disk != null) Stat("Free space", formatSize(disk.usableBytes))
    }

    val networks = settings.networks
    if (networks != null && networks.supported && networks.available.isNotEmpty()) {
      InspectorSection("Networks") {
        val selected = networks.config.interfaceIds
        FlowRow(
          horizontalArrangement = Arrangement.spacedBy(spacing.s2),
          verticalArrangement = Arrangement.spacedBy(spacing.s2),
        ) {
          val open = { state.openSettings(SettingsTarget(SettingsTarget.Page.Network, deviceId)) }
          val names = selected.map { id ->
            networks.available.firstOrNull { it.id == id }?.name ?: id
          }
          for (name in names.ifEmpty { listOf("System default") }) {
            KetchChip(
              label = name,
              selected = false,
              leadingIcon = KetchIcon.Network,
              onClick = open,
            )
          }
        }
      }
    }

    InspectorSection("Up next") {
      if (summary.upNext.isEmpty()) {
        Text(
          text = "Nothing is waiting. Select a download to see its details here.",
          style = type.bodyS,
          color = colors.textTertiary,
        )
      }
      for (row in summary.upNext) UpNextRow(state, row)
    }
  }
}

/**
 * The overview of every device at once: their total speed over the last minute, one band per
 * device in its pennant hue, a line per device that shows it alone when clicked, connections in
 * flight, and Up next across them.
 */
@Composable
private fun FleetOverview(state: AppState, onClose: () -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val instances by state.instances.collectAsState()
  val rows by state.taskList.rows.collectAsState()
  val pulse by state.pulse.state.collectAsState()
  val histories by state.speedHistory.histories.collectAsState()
  val summary = remember(rows) { scopeSummary(rows, config = null) }
  val labels = instances.associate { it.deviceId to it.label }

  Column(verticalArrangement = Arrangement.spacedBy(spacing.s4)) {
    Row(horizontalArrangement = Arrangement.spacedBy(spacing.s3)) {
      StackedPennants(
        devices = pulse.devices.map { it.deviceId to (labels[it.deviceId] ?: it.name) },
        size = DevicePennantDefaults.Medium,
        modifier = Modifier.padding(top = spacing.s0_5),
      )
      Column(
        verticalArrangement = Arrangement.spacedBy(spacing.s0_5),
        modifier = Modifier.weight(1f),
      ) {
        Text(
          text = "All devices",
          style = type.titleM,
          color = colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
        Text(
          text = keepPartsTogether(pulse.sentence(now = LocalClock.current.now())),
          style = type.caption,
          color = colors.textSecondary,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
        )
      }
      KetchIconButton(
        icon = KetchIcon.Close,
        contentDescription = "Close inspector",
        onClick = onClose,
        size = KetchButtonSize.Small,
      )
    }

    Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
      Text(
        text = formatSpeed(pulse.totalSpeed),
        style = type.numeralL,
        color = colors.textPrimary,
      )
      val clock = LocalClock.current
      // Every download of the last minute counts, finished ones too, each at the time it ran.
      val bands = remember(histories, rows, colors) {
        val now = clock.now()
        rows.groupBy { it.key.deviceId }.mapNotNull { (deviceId, own) ->
          val total = totalHistory(own.mapNotNull { histories[it.key] }, now)
          val samples = total.samples.takeLast(CHART_SECONDS)
          if (samples.none { it > 0 }) return@mapNotNull null
          val hue = colors.deviceHue(deviceId)
          SpeedBand(samples, if (colors.isDark) hue.dark else hue.light)
        }
      }
      KetchSpeedChart(
        bands = bands,
        slots = CHART_SECONDS,
        modifier = Modifier.fillMaxWidth().height(spacing.s16),
      )
      Row {
        Text(
          text = "1 min ago",
          style = type.numeralS,
          color = colors.textTertiary,
          modifier = Modifier.weight(1f),
        )
        Text(text = "Now", style = type.numeralS, color = colors.textTertiary)
      }
    }

    InspectorSection("Devices") {
      for (device in pulse.devices) {
        val entry = instances.firstOrNull { it.deviceId == device.deviceId } ?: continue
        DeviceLine(
          device = device,
          pennantName = entry.label,
          onClick = { state.switchInstance(entry) },
        )
      }
    }

    InspectorSection("In flight") {
      Stat("Connections", connectionsText(summary))
      Stat("Downloading", "${summary.running}")
    }

    InspectorSection("Up next") {
      if (summary.upNext.isEmpty()) {
        Text(
          text = "Nothing is waiting on any device.",
          style = type.bodyS,
          color = colors.textTertiary,
        )
      }
      for (row in summary.upNext) UpNextRow(state, row, showDevice = true)
    }
  }
}

/**
 * One device of the [FleetOverview]: its pennant with its health, its name and what it does,
 * such as "2 downloading · 1 failed · 412 GB free", and its speed; clicking it shows it alone.
 */
@Composable
private fun DeviceLine(device: DevicePulse, pennantName: String, onClick: () -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val shape = KetchTheme.shapes.md
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier
      .fillMaxWidth()
      .focusRing(focus.visible, shape, colors.focusRing)
      .clip(shape)
      .background(overlay)
      .trackFocusVisibility(focus)
      .clickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClickLabel = "Show ${device.name} alone",
        onClick = onClick,
      )
      .padding(horizontal = spacing.s1, vertical = spacing.s1),
  ) {
    DevicePennant(
      deviceId = device.deviceId,
      name = pennantName,
      size = DevicePennantDefaults.Small,
      health = device.health,
    )
    Column(Modifier.weight(1f)) {
      Text(
        text = device.name,
        style = type.bodyStrong,
        color = colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      Text(
        text = keepPartsTogether(deviceLine(device)),
        style = type.caption,
        color = if (device.health.isOnline) colors.textSecondary else colors.status.failed.color,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
    if (device.health.isOnline && device.speed > 0) {
      Text(text = formatSpeed(device.speed), style = type.numeral, color = colors.textPrimary)
    }
  }
}

/** What [device] does, such as "2 downloading · 1 failed · 412 GB free", or why it is away. */
internal fun deviceLine(device: DevicePulse): String = when (device.health) {
  DeviceHealth.Connecting -> "Connecting…"
  DeviceHealth.Unauthorized -> "Needs a new access token"
  is DeviceHealth.Offline -> "Offline"
  else -> {
    val counts = device.counts
    listOfNotNull(
      "${counts.downloading} downloading".takeIf { counts.downloading > 0 },
      "${counts.waiting} waiting".takeIf { counts.waiting > 0 },
      "${device.failures} failed".takeIf { device.failures > 0 },
      "Idle".takeIf { counts.downloading == 0 && counts.waiting == 0 && device.failures == 0 },
      device.disk?.let { "${formatSize(it.usableBytes)} free" },
    ).joinToString(" · ")
  }
}

private fun connectionsText(summary: ScopeSummary): String {
  if (summary.transfers == 0) return "None"
  return "${summary.connections} across ${plural(summary.transfers, "download")}"
}

@Composable
private fun Stat(label: String, value: String) {
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s0_5),
  ) {
    Text(
      text = label,
      style = KetchTheme.typography.bodyS,
      color = KetchTheme.colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    Text(text = value, style = KetchTheme.typography.numeral, color = KetchTheme.colors.textPrimary)
  }
}

/**
 * A waiting download: its chip, name and why it waits, after its device when [showDevice], and
 * Start now.
 */
@Composable
private fun UpNextRow(state: AppState, row: TaskRow, showDevice: Boolean = false) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier.fillMaxWidth().padding(vertical = spacing.s0_5),
  ) {
    KetchFileTypeChip(
      fileName = row.name,
      sourceUrl = row.request.url,
      size = KetchFileTypeChipDefaults.ListSize,
    )
    Column(modifier = Modifier.weight(1f)) {
      MiddleText(row.name, KetchTheme.typography.bodyS, colors.textPrimary)
      Text(
        text = if (showDevice) {
          "${row.device.name} · ${row.content.detail}"
        } else {
          row.content.detail
        },
        style = KetchTheme.typography.caption,
        color = colors.textTertiary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
    val canStart = row.state !is DownloadState.Scheduled || row.device.capabilities.canReschedule
    KetchIconButton(
      icon = KetchIcon.Play,
      contentDescription = "Start ${row.name} now",
      onClick = { state.startNow(row.task) },
      enabled = canStart,
      size = KetchButtonSize.Small,
    )
  }
}

private const val CHART_SECONDS = 60
