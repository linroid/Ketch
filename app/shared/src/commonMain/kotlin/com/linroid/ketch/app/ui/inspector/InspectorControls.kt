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
import com.linroid.ketch.app.components.offPeakStart
import com.linroid.ketch.app.components.winningLimitCaption
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.actions.RowActionRunner
import com.linroid.ketch.app.ui.downloads.actions.connectionEntries
import com.linroid.ketch.app.ui.list.RowCommands
import com.linroid.ketch.app.util.SegmentRateTracker
import com.linroid.ketch.app.util.priorityLabel
import kotlinx.coroutines.delay
import kotlinx.datetime.TimeZone
import kotlin.time.Clock
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
  val globalName = if (slowLane) "Slow lane" else "Global limit"
  fun isPending(label: (TaskRow) -> String): Boolean =
    single != null && (single.key to label(single)) in pending

  InspectorSection("Controls", modifier) {
    BoxWithConstraints(Modifier.fillMaxWidth()) {
      val inline = maxWidth >= InspectorLabelWidth + spacing.inspectorWidth
      Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
        ControlRow("Speed", inline, wide = true) {
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
              "$MIXED the downloads have different limits"
            } else {
              winningLimitCaption(shared.speedLimit, cap, globalName)
            }
            if (caption != null) Caption(caption)
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
  val value = shared.connections
  ControlRow(if (torrent) "Peer limit" else "Connections", inline) {
    when {
      value == null -> MixedChip(title = "Connections") {
        connectionEntries(rows, runner, peers = torrent)
      }
      torrent -> ConnectionStepper(
        value = value,
        onCommit = { runner.setConnections(rows, it) },
        range = PeerLimitRange,
        step = PEER_LIMIT_STEP,
        pending = pending,
        noun = "peers",
      )
      else -> {
        val single = rows.singleOrNull()
        val limited = single != null && rememberServerLimited(single)
        val auto = state.instanceSettings.download?.maxConnectionsPerDownload?.takeIf { it > 0 }
          ?: single?.segments?.size?.takeIf { it > 0 }
        ConnectionStepper(
          value = value,
          onCommit = { runner.setConnections(rows, it) },
          autoValue = auto,
          enabled = !limited,
          pending = pending,
          disabledReason = if (limited) SERVER_LIMIT else null,
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
private fun rememberServerLimited(row: TaskRow): Boolean {
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
    waiting.forEach { state.startNow(it.task) }
    val running = rows - waiting.toSet()
    if (running.isNotEmpty()) runner.setPriority(running, DownloadPriority.URGENT)
  }
  ControlRow("Priority", inline, wide = true) {
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
          label = { priority -> priority?.let(::priorityLabel).orEmpty() },
          icon = { priority ->
            KetchIcon.Bolt.takeIf { variant == 0 && priority == DownloadPriority.URGENT }
          },
        )
      }
      if (shared.priority == null) Caption("$MIXED the downloads have different priorities")
    }
  }
  val victim = asking
  if (victim != null) {
    ConfirmNote(
      text = urgentNote(victim, waiting.size),
      confirm = if (waiting.size == 1) "Start now" else "Start ${waiting.size} now",
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
  val keys = rows.mapTo(HashSet()) { it.key }
  val running = state.taskList.rows.value.filter {
    it.key.deviceId == deviceId && it.key !in keys && it.state is DownloadState.Downloading
  }
  return preemptionVictim(running, state.instanceSettings.download?.maxConcurrentDownloads)
}

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
    offPeakStart(speedSettings.rules, Clock.System.now())
  } else {
    null
  }
  var asking by remember(rows.map { it.key }) { mutableStateOf<DownloadSchedule?>(null) }
  val running = rows.any { it.state is DownloadState.Downloading }
  val select: (DownloadSchedule) -> Unit = { schedule ->
    if (running && schedule is DownloadSchedule.AtTime) {
      asking = schedule
    } else {
      runner.reschedule(rows, schedule)
    }
  }
  ControlRow("Start", inline) {
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
        disabledReason = REMOTE_SCHEDULE.takeUnless { canReschedule },
      )
    }
  }
  val schedule = asking
  if (schedule != null) {
    ConfirmNote(
      text = rescheduleNote(schedule, Clock.System.now(), zone),
      confirm = "Reschedule",
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
  text: String,
  confirm: String,
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
    Text(text = text, style = KetchTheme.typography.bodyS, color = colors.textPrimary)
    FlowRow(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
      KetchButton(
        text = confirm,
        onClick = onConfirm,
        variant = KetchButtonVariant.Tonal,
        size = KetchButtonSize.Small,
      )
      KetchButton(
        text = "Cancel",
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
private const val SERVER_LIMIT = "This server allows only 1 connection"
private const val REMOTE_SCHEDULE = "Scheduling remote downloads isn't supported yet"
