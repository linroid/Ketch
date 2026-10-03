package com.linroid.ketch.app.ui.inspector

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.ConnectionStepper
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchMenuScope
import com.linroid.ketch.app.components.KetchSegmented
import com.linroid.ketch.app.components.PEER_LIMIT_STEP
import com.linroid.ketch.app.components.PeerLimitRange
import com.linroid.ketch.app.components.StartTimeMenu
import com.linroid.ketch.app.components.StartTimePicker
import com.linroid.ketch.app.components.StepperCount
import com.linroid.ketch.app.components.offPeakStart
import com.linroid.ketch.app.components.winningLimitCaption
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.priorityText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.actions.RowActionRunner
import com.linroid.ketch.app.ui.downloads.actions.connectionEntries
import com.linroid.ketch.app.ui.list.RowCommands
import com.linroid.ketch.app.util.SegmentRateTracker
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_cancel
import ketch.app.shared.generated.resources.inspector_control_connections
import ketch.app.shared.generated.resources.inspector_control_peer_limit
import ketch.app.shared.generated.resources.inspector_control_priority
import ketch.app.shared.generated.resources.inspector_control_speed
import ketch.app.shared.generated.resources.inspector_control_start
import ketch.app.shared.generated.resources.inspector_global_limit
import ketch.app.shared.generated.resources.inspector_global_slow_lane
import ketch.app.shared.generated.resources.inspector_mixed_limits
import ketch.app.shared.generated.resources.inspector_mixed_priorities
import ketch.app.shared.generated.resources.inspector_remote_schedule
import ketch.app.shared.generated.resources.inspector_reschedule
import ketch.app.shared.generated.resources.inspector_section_controls
import ketch.app.shared.generated.resources.inspector_server_one_connection
import ketch.app.shared.generated.resources.inspector_start_count_now
import ketch.app.shared.generated.resources.inspector_start_now
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import org.jetbrains.compose.resources.stringResource
import kotlin.time.Instant

/**
 * The CONTROLS of the inspector for one download, or the shared ones of several selected
 * [rows]: Speed, Connections (Peer limit for torrents), Priority and Start. A control whose
 * value differs between the rows shows "—" and still sets them all.
 *
 * Choosing Urgent for a waiting download while every slot is taken first says which download
 * it may pause; rescheduling a running one first says that it pauses now. Start is disabled on
 * devices that cannot reschedule, such as remote ones.
 */
@Composable
internal fun InspectorControls(
  state: AppState,
  rows: List<TaskRow>,
  runner: RowActionRunner,
  pending: Set<Pair<TaskKey, String>>,
  modifier: Modifier = Modifier,
) {
  if (rows.isEmpty()) return
  val spacing = KetchTheme.spacing
  val single = rows.singleOrNull()
  val shared = SharedSettings.of(rows)
  val deviceId = rows.first().key.deviceId
  val pulse by state.pulse.state.collectAsState()
  val cap = pulse.devices.firstOrNull { it.deviceId == deviceId }?.cap ?: SpeedLimit.Unlimited
  val slowLane = deviceId == LOCAL_DEVICE_ID && pulse.mode.isSlowLane
  val globalName = if (slowLane) {
    Res.string.inspector_global_slow_lane.text()
  } else {
    Res.string.inspector_global_limit.text()
  }
  fun isPending(label: (TaskRow) -> String): Boolean =
    single != null && (single.key to label(single)) in pending

  InspectorSection(stringResource(Res.string.inspector_section_controls), modifier) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val inline = maxWidth >= InspectorLabelWidth + spacing.inspectorWidth
      Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
        ControlRow(stringResource(Res.string.inspector_control_speed), inline, wide = true) {
          Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
            val presets = remember(single?.key) {
              speedPresets(single?.let { state.speedHistory.history(it.key)?.peak ?: it.speed })
            }
            SpeedControl(
              value = shared.speedLimit,
              presets = presets,
              onCommit = { runner.setSpeedLimit(rows, it) },
              globalCap = cap,
              globalName = globalName,
              pending = isPending(RowCommands::speedLimitLabel),
            )
            val caption = if (shared.speedLimit == null) {
              Res.string.inspector_mixed_limits.text()
            } else {
              winningLimitCaption(shared.speedLimit, cap, globalName)
            }
            if (caption != null) Caption(caption.resolve())
          }
        }
        val connectionsPending = isPending(RowCommands::connectionsLabel)
        ConnectionsRow(state, rows, shared, runner, inline, connectionsPending)
        PriorityRow(state, rows, shared, runner, inline, isPending(RowCommands::priorityLabel))
        StartRow(state, rows, shared, runner, inline, isPending(RowCommands::rescheduleLabel))
      }
    }
  }
}

@Composable
private fun ConnectionsRow(
  state: AppState,
  rows: List<TaskRow>,
  shared: SharedSettings,
  runner: RowActionRunner,
  inline: Boolean,
  pending: Boolean,
) {
  val torrent = rows.all { it.isTorrent }
  // A torrent's count is its peer limit, so a mixed selection sets the HTTP and FTP ones only.
  val targets = if (torrent) rows else rows.filter { !it.isTorrent }
  val value = if (targets.size == rows.size) {
    shared.connections
  } else {
    targets.map { it.request.connections }.distinct().singleOrNull()
  }
  val connections = stringResource(Res.string.inspector_control_connections)
  val label = if (torrent) stringResource(Res.string.inspector_control_peer_limit) else connections
  // Collected so the row offers Auto once a remote device has reported what it supports.
  val presence by state.instanceManager.presence.collectAsState()
  val autoSupported = remember(presence, targets) { autoConnectionsSupported(state, targets) }
  ControlRow(label, inline) {
    when {
      value == null -> MixedChip(title = connections) {
        connectionEntries(
          rows = targets,
          runner = runner,
          peers = torrent,
          auto = if (torrent) null else autoConnectionsOf(state, targets),
          allowAuto = autoSupported,
        )
      }
      // The app does not know a torrent's default peer limit, so its Auto shows no number.
      torrent -> ConnectionStepper(
        value = value,
        onCommit = { runner.setConnections(rows, it) },
        range = PeerLimitRange,
        step = PEER_LIMIT_STEP,
        pending = pending,
        counts = StepperCount.Peers,
        allowAuto = autoSupported,
      )
      else -> {
        val single = rows.singleOrNull()
        val limited = single != null && rememberServerLimited(single)
        val auto = autoConnectionsOf(state, targets)
          ?: single?.segments?.size?.takeIf { it > 0 }
        ConnectionStepper(
          value = value,
          onCommit = { runner.setConnections(targets, it) },
          autoValue = auto,
          enabled = !limited,
          pending = pending,
          disabledReason = if (limited) {
            stringResource(Res.string.inspector_server_one_connection)
          } else {
            null
          },
          allowAuto = autoSupported,
        )
      }
    }
  }
}

/**
 * Whether [row]'s server allows a single connection: its source says so, or it kept one
 * segment for a while after more were asked for.
 */
@Composable
internal fun rememberServerLimited(row: TaskRow): Boolean {
  val single = row.state is DownloadState.Downloading && row.request.connections > 1 &&
    row.segments.size == 1
  var confirmed by remember(row.key) { mutableStateOf(false) }
  LaunchedEffect(row.key, single) {
    confirmed = false
    if (single) {
      delay(SegmentRateTracker.STALL_AFTER)
      confirmed = true
    }
  }
  return row.request.resolvedSource?.maxSegments == 1 || confirmed
}

@Composable
private fun PriorityRow(
  state: AppState,
  rows: List<TaskRow>,
  shared: SharedSettings,
  runner: RowActionRunner,
  inline: Boolean,
  pending: Boolean,
) {
  val spacing = KetchTheme.spacing
  val (shown, choose) = rememberChoice(shared.priority, pending)
  var asking by remember(rows.map { it.key }) { mutableStateOf<TaskRow?>(null) }
  val waiting = rows.filter { it.state !is DownloadState.Downloading }
  fun urgent() {
    asking = null
    choose(DownloadPriority.URGENT)
    if (waiting.isNotEmpty()) state.startNow(waiting.map { it.task })
    val running = rows - waiting.toSet()
    if (running.isNotEmpty()) runner.setPriority(running, DownloadPriority.URGENT)
  }
  ControlRow(stringResource(Res.string.inspector_control_priority), inline, wide = true) {
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
      FirstThatFits(count = 2) { variant ->
        KetchSegmented(
          options = Priorities,
          selected = shown,
          onSelect = { priority ->
            when {
              priority == null || priority == shown -> Unit
              priority == DownloadPriority.URGENT && waiting.isNotEmpty() -> {
                val victim = victimFor(state, rows)
                if (victim != null) asking = victim else urgent()
              }
              priority == DownloadPriority.URGENT -> urgent()
              else -> {
                choose(priority)
                runner.setPriority(rows, priority)
              }
            }
          },
          label = { priority -> priority?.let { priorityText(it).resolve() }.orEmpty() },
          icon = { priority ->
            KetchIcon.Bolt.takeIf { variant == 0 && priority == DownloadPriority.URGENT }
          },
        )
      }
      if (shared.priority == null) {
        Caption(stringResource(Res.string.inspector_mixed_priorities))
      }
    }
  }
  val victim = asking
  if (victim != null) {
    ConfirmNote(
      text = urgentNote(victim, waiting.size),
      confirm = if (waiting.size == 1) {
        Res.string.inspector_start_now.text()
      } else {
        Res.plurals.inspector_start_count_now.text(waiting.size)
      },
      onConfirm = ::urgent,
      onCancel = { asking = null },
    )
  }
}

/**
 * The download starting [rows] now would pause: the lowest-priority one running on their
 * device when every slot is taken.
 */
private fun victimFor(state: AppState, rows: List<TaskRow>): TaskRow? {
  val deviceId = rows.first().key.deviceId
  val running = state.taskList.rows.value.filter {
    it.key.deviceId == deviceId && it.state is DownloadState.Downloading
  }
  val slots = state.settingsOf(deviceId)?.download?.maxConcurrentDownloads
  return preemptionVictim(running, slots, starting = rows.mapTo(HashSet()) { it.key })
}

/**
 * The connections Auto gives [rows] on their device, from its settings; `null` while they are
 * unknown or the rows are on several devices.
 */
internal fun autoConnectionsOf(state: AppState, rows: List<TaskRow>): Int? {
  val deviceId = rows.map { it.key.deviceId }.distinct().singleOrNull() ?: return null
  return state.settingsOf(deviceId)?.download?.maxConnectionsPerDownload?.takeIf { it > 0 }
}

/**
 * Whether the devices of every one of [rows] take 0 (Auto) connections; older devices only take
 * a count.
 */
internal fun autoConnectionsSupported(state: AppState, rows: List<TaskRow>): Boolean =
  rows.all { KetchFeatures.AUTO_CONNECTIONS in state.featuresOf(it.key.deviceId) }

@Composable
private fun StartRow(
  state: AppState,
  rows: List<TaskRow>,
  shared: SharedSettings,
  runner: RowActionRunner,
  inline: Boolean,
  pending: Boolean,
) {
  val zone = remember { TimeZone.currentSystemDefault() }
  val canReschedule = rows.all { it.device.capabilities.canReschedule }
  val local = rows.first().key.deviceId == LOCAL_DEVICE_ID
  val speedSettings = state.speedMode?.settings?.collectAsState()?.value
  val offPeak = if (local && speedSettings != null) {
    offPeakStart(speedSettings.rules, LocalClock.current.now())
  } else {
    null
  }
  var asking by remember(rows.map { it.key }) { mutableStateOf<DownloadSchedule?>(null) }
  val running = rows.any { it.state is DownloadState.Downloading }
  val select: (DownloadSchedule) -> Unit = { schedule ->
    // Rescheduling a running download to now would only pause it and queue it again.
    val targets = if (schedule == DownloadSchedule.Immediate) {
      rows.filter { it.state !is DownloadState.Downloading }
    } else {
      rows
    }
    when {
      targets.isEmpty() || schedule == shared.schedule -> Unit
      running && schedule is DownloadSchedule.AtTime -> asking = schedule
      else -> runner.reschedule(targets, schedule)
    }
  }
  ControlRow(stringResource(Res.string.inspector_control_start), inline) {
    val value = shared.schedule
    if (value == null) {
      MixedStartChip(onSelect = select, offPeak = offPeak, enabled = canReschedule)
    } else {
      StartTimePicker(
        value = value,
        onSelect = select,
        enabled = canReschedule,
        offPeak = offPeak,
        pending = pending,
        disabledReason = if (canReschedule) {
          null
        } else {
          stringResource(Res.string.inspector_remote_schedule)
        },
      )
    }
  }
  val schedule = asking
  if (schedule != null) {
    ConfirmNote(
      text = rescheduleNote(schedule, LocalClock.current.now(), zone),
      confirm = Res.string.inspector_reschedule.text(),
      onConfirm = {
        asking = null
        runner.reschedule(rows, schedule)
      },
      onCancel = { asking = null },
    )
  }
}

/** "—" for a value the selected downloads do not share; its [menu] sets them all. */
@Composable
private fun MixedChip(title: String, menu: KetchMenuScope.() -> Unit) {
  var open by remember { mutableStateOf(false) }
  Box {
    KetchChip(
      label = MIXED,
      selected = false,
      trailingIcon = KetchIcon.ChevronDown,
      onClick = { open = true },
    )
    KetchMenu(expanded = open, onDismissRequest = { open = false }, title = title, content = menu)
  }
}

@Composable
private fun MixedStartChip(
  onSelect: (DownloadSchedule) -> Unit,
  offPeak: Instant?,
  enabled: Boolean,
) {
  var open by remember { mutableStateOf(false) }
  Box {
    KetchChip(
      label = MIXED,
      selected = false,
      enabled = enabled,
      trailingIcon = KetchIcon.ChevronDown,
      onClick = { open = true },
    )
    StartTimeMenu(
      expanded = open,
      onDismissRequest = { open = false },
      value = DownloadSchedule.Immediate,
      onSelect = {
        open = false
        onSelect(it)
      },
      offPeak = offPeak,
    )
  }
}

/** A note that asks before a choice applies, with [confirm] and Cancel. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ConfirmNote(
  text: UiText,
  confirm: UiText,
  onConfirm: () -> Unit,
  onCancel: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.status.paused.soft, KetchTheme.shapes.sm)
      .padding(spacing.s2),
  ) {
    Text(text = text.resolve(), style = KetchTheme.typography.bodyS, color = colors.textPrimary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
      KetchButton(
        text = confirm.resolve(),
        onClick = onConfirm,
        variant = KetchButtonVariant.Tonal,
        size = KetchButtonSize.Small,
      )
      KetchButton(
        text = stringResource(Res.string.action_cancel),
        onClick = onCancel,
        variant = KetchButtonVariant.Ghost,
        size = KetchButtonSize.Small,
      )
    }
  }
}

@Composable
private fun Caption(text: String) {
  Text(text = text, style = KetchTheme.typography.caption, color = KetchTheme.colors.textSecondary)
}

private val Priorities = listOf(
  DownloadPriority.LOW,
  DownloadPriority.NORMAL,
  DownloadPriority.HIGH,
  DownloadPriority.URGENT,
)

private const val MIXED = "—"
