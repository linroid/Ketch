package com.linroid.ketch.app.ui.inspector

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
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.state.toDeviceHealth
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.inspector.tabs.formatSize
import com.linroid.ketch.app.ui.inspector.tabs.formatSpeed
import com.linroid.ketch.app.ui.inspector.tabs.plural

/**
 * What the inspector shows with no download selected: the active device's speed over the last
 * minute, connections in flight, slots and per-site limits in use, free space, the networks it
 * downloads over, and Up next, the first waiting downloads, each with Start now.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ScopeOverview(state: AppState, onClose: () -> Unit) {
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

/** A waiting download: its chip, name and why it waits, and Start now. */
@Composable
private fun UpNextRow(state: AppState, row: TaskRow) {
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
        text = row.content.detail,
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
