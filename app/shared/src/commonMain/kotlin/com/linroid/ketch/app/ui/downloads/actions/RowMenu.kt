package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.absoluteOffset
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.IntOffset
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.DeviceOption
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchMenuScope
import com.linroid.ketch.app.components.deviceOptionCaption
import com.linroid.ketch.app.components.startTimeOptions
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.state.toDeviceHealth
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.priorityLabel
import kotlin.math.roundToInt
import kotlin.time.Clock
import kotlin.time.Instant
import kotlinx.datetime.TimeZone

/**
 * A context menu open on the Downloads list.
 *
 * @property anchor the row the menu belongs to.
 * @property rows the rows the menu acts on: the selection, or the anchor alone.
 * @property position where the pointer was, from the anchor row's top-left corner; `null` when
 *   the row's "⋯" hover button opened it, under that button.
 */
@Immutable
internal data class RowMenuRequest(
  val anchor: TaskKey,
  val rows: List<TaskRow>,
  val position: Offset?,
)

/**
 * Which row of the Downloads list has its menu open, if any: one at a time, opened by a
 * right-click ([RowMenuAnchor]) or by the row's "⋯" ([HoverActions]). The list's order stays
 * still and its keys wait while [isOpen].
 */
@Stable
internal class RowMenuState {
  /** The open menu, or `null`. */
  var request: RowMenuRequest? by mutableStateOf(null)
    private set

  /** Whether a menu is open. */
  val isOpen: Boolean get() = request != null

  /**
   * Opens the menu of [rows] on the row of [anchor]: at [position] in that row, or under the
   * row's "⋯" when `null`. Any other open menu closes.
   */
  fun open(anchor: TaskKey, rows: List<TaskRow>, position: Offset? = null) {
    if (rows.isNotEmpty()) request = RowMenuRequest(anchor, rows, position)
  }

  /** Closes the menu. */
  fun close() {
    request = null
  }

  /** Closes the menu of [opened] unless another one took its place. */
  fun closeIfCurrent(opened: RowMenuRequest) {
    if (request === opened) request = null
  }
}

/**
 * The context menu of the row of [key] while [menu] is open on it at the pointer. Place it in a
 * `Box` around the row's content. A row that scrolls away or is removed closes its menu, so the
 * list never stays frozen behind a menu nobody sees.
 */
@Composable
internal fun RowMenuAnchor(menu: RowMenuState, key: TaskKey, runner: RowActionRunner) {
  val request = menu.request?.takeIf { it.anchor == key } ?: return
  val position = request.position ?: return
  DisposableEffect(menu, request) { onDispose { menu.closeIfCurrent(request) } }
  // Pointer positions do not mirror in right-to-left layouts, so neither does the anchor.
  Box(Modifier.absoluteOffset { IntOffset(position.x.roundToInt(), position.y.roundToInt()) }) {
    // Cancels the menu's gap below its anchor, so its corner sits at the pointer.
    RowMenu(
      expanded = true,
      onDismissRequest = menu::close,
      rows = request.rows,
      runner = runner,
      offset = DpOffset.Zero.copy(y = -KetchTheme.spacing.s1),
    )
  }
}

/**
 * The menu of [rows]: everything one row offers, from [RowActionRunner.menu], or for several
 * rows the actions that apply to any of them with how many they apply to, such as "Pause 3
 * downloads". Speed limit, Connections, Priority, Start later and Send to open submenus.
 * Destructive actions come last, after a divider. With a pointer it is a popup like
 * [KetchMenu]; on touch it is a bottom sheet titled with the download.
 */
@Composable
internal fun RowMenu(
  expanded: Boolean,
  onDismissRequest: () -> Unit,
  rows: List<TaskRow>,
  runner: RowActionRunner,
  modifier: Modifier = Modifier,
  offset: DpOffset = DpOffset.Zero,
) {
  if (!expanded || rows.isEmpty()) return
  val single = rows.singleOrNull()
  LaunchedEffect(single?.key) { single?.let(runner::checkFile) }
  val instances by runner.state.instances.collectAsState()
  val context = RowMenuContext(
    revealLabel = runner.files?.revealLabel,
    devices = sendTargets(instances, rows),
    now = Clock.System.now(),
    zone = TimeZone.currentSystemDefault(),
    urgentVictim = urgentVictim(rows, runner),
  )
  val title = single?.name ?: downloads(rows.size)
  KetchMenu(
    expanded = true,
    onDismissRequest = onDismissRequest,
    modifier = modifier,
    offset = offset,
    title = title,
  ) {
    rowMenuEntries(rows, runner, context)
  }
}

/**
 * A device rows can be sent to.
 *
 * @property entry the device.
 * @property option its name and health, as device menus list them.
 */
internal data class SendTarget(val entry: InstanceEntry, val option: DeviceOption)

/**
 * What a row menu needs besides its rows.
 *
 * @property revealLabel "Show in Finder" and the like; `null` where files cannot be revealed.
 * @property devices other devices the rows can be sent to.
 * @property now the current time, for Start later.
 * @property zone the local time zone, for Start later.
 * @property urgentVictim name of the download Urgent would pause to make room, if any.
 */
internal data class RowMenuContext(
  val revealLabel: String? = null,
  val devices: List<SendTarget> = emptyList(),
  val now: Instant = Clock.System.now(),
  val zone: TimeZone = TimeZone.currentSystemDefault(),
  val urgentVictim: String? = null,
)

/** Adds the entries of the menu of [rows]; see [RowMenu]. */
internal fun KetchMenuScope.rowMenuEntries(
  rows: List<TaskRow>,
  runner: RowActionRunner,
  context: RowMenuContext,
) {
  val single = rows.singleOrNull()
  val entries: List<Pair<RowAction, List<TaskRow>>> = if (single != null) {
    if (runner.isFileMissing(single)) {
      item(label = "File moved or deleted", onClick = {}, icon = KetchIcon.Warning, enabled = false)
    }
    runner.menu(single).map { it to rows }
  } else {
    runner.batch(rows).map { it.action to it.rows }
  }
  var destructive = false
  for ((action, targets) in entries) {
    if (action.destructive && !destructive) {
      divider()
      destructive = true
    }
    val label = if (single != null) {
      rowActionLabel(action, context.revealLabel)
    } else {
      batchLabel(action, targets.size, context.revealLabel)
    }
    when (action) {
      RowAction.SpeedLimit -> submenu(label, action.icon) { speedEntries(targets, runner) }
      RowAction.Connections -> {
        val peers = targets.all { it.isTorrent }
        val name = if (single != null && peers) "Peer limit" else label
        // A connection count is no peer limit, so a mixed selection leaves its torrents alone.
        val counted = if (peers) targets else targets.filterNot { it.isTorrent }
        submenu(name, action.icon) { connectionEntries(counted, runner, peers) }
      }
      RowAction.Priority -> submenu(label, action.icon) {
        priorityEntries(targets, runner, context.urgentVictim)
      }
      RowAction.StartLater -> submenu(label, action.icon) {
        startLaterEntries(targets, runner, context)
      }
      RowAction.SendTo -> if (context.devices.isNotEmpty()) {
        submenu(label, action.icon) { sendEntries(targets, runner, context.devices) }
      }
      else -> item(
        label = label,
        onClick = { runner.run(action, rows) },
        icon = action.icon,
        shortcut = action.command?.shortcutLabel(KeyboardPlatform.current),
        destructive = action.destructive,
      )
    }
  }
}

/** Speed limits a row menu offers, before Custom…. */
internal val MenuSpeedLimits: List<SpeedLimit> = listOf(
  SpeedLimit.Unlimited,
  SpeedLimit.kbps(256),
  SpeedLimit.kbps(512),
  SpeedLimit.mbps(1),
  SpeedLimit.mbps(2),
  SpeedLimit.mbps(5),
  SpeedLimit.mbps(10),
)

/** Totals a selection can share, each download getting an equal part. */
internal val SharedSpeedLimits: List<SpeedLimit> = listOf(
  SpeedLimit.mbps(1),
  SpeedLimit.mbps(2),
  SpeedLimit.mbps(5),
  SpeedLimit.mbps(10),
  SpeedLimit.mbps(20),
)

/** Connection counts a row menu offers; torrents get [PeerLimits]. */
internal val ConnectionCounts: List<Int> = listOf(1, 2, 4, 8, 16, 32)

/** Peer limits a torrent's row menu offers. */
internal val PeerLimits: List<Int> = listOf(10, 20, 50, 100, 200, 500)

/** Adds the speed limit choices for [rows], with "Share across these n" for several. */
internal fun KetchMenuScope.speedEntries(rows: List<TaskRow>, runner: RowActionRunner) {
  val current = rows.map { it.request.speedLimit }.distinct().singleOrNull()
  for (limit in MenuSpeedLimits) {
    item(
      label = formatSpeedLimit(limit),
      onClick = { runner.setSpeedLimit(rows, limit) },
      checked = limit == current,
    )
  }
  item(label = "Custom…", onClick = { runner.requestCustomSpeed(rows) })
  if (rows.size > 1) {
    divider()
    submenu("Share across these ${rows.size}", KetchIcon.Lanes) {
      for (total in SharedSpeedLimits) {
        val each = SpeedLimit.of((total.bytesPerSecond / rows.size).coerceAtLeast(1))
        item(
          label = formatSpeedLimit(total),
          caption = "${formatSpeedLimit(each)} each",
          onClick = { runner.shareSpeed(rows, total) },
        )
      }
    }
  }
}

/** Adds the connection counts, or the peer limits when [peers], for [rows]. */
internal fun KetchMenuScope.connectionEntries(
  rows: List<TaskRow>,
  runner: RowActionRunner,
  peers: Boolean,
) {
  val current = rows.map { it.request.connections }.distinct().singleOrNull()
  for (count in if (peers) PeerLimits else ConnectionCounts) {
    item(
      label = count.toString(),
      onClick = { runner.setConnections(rows, count) },
      checked = count == current,
    )
  }
}

/**
 * Adds the priorities for [rows]. Urgent starts a waiting or paused download now, which may
 * pause [victim] to make room; a running one keeps its slot with the highest priority.
 */
internal fun KetchMenuScope.priorityEntries(
  rows: List<TaskRow>,
  runner: RowActionRunner,
  victim: String?,
) {
  val current = rows.map { it.request.priority }.distinct().singleOrNull()
  val waiting = rows.filter { it.state !is DownloadState.Downloading }
  val caption = when {
    waiting.isEmpty() -> null
    victim != null -> "Starts now · may pause $victim"
    else -> "Starts now"
  }
  item(
    label = priorityLabel(DownloadPriority.URGENT),
    onClick = {
      waiting.forEach { runner.state.startNow(it.task) }
      val running = rows - waiting.toSet()
      if (running.isNotEmpty()) runner.setPriority(running, DownloadPriority.URGENT)
    },
    icon = KetchIcon.Bolt,
    caption = caption,
    checked = current == DownloadPriority.URGENT,
  )
  for (priority in listOf(DownloadPriority.HIGH, DownloadPriority.NORMAL, DownloadPriority.LOW)) {
    item(
      label = priorityLabel(priority),
      onClick = { runner.setPriority(rows, priority) },
      checked = priority == current,
    )
  }
}

/** Adds the start times for [rows] and "Pick date & time…". */
internal fun KetchMenuScope.startLaterEntries(
  rows: List<TaskRow>,
  runner: RowActionRunner,
  context: RowMenuContext,
) {
  val current = rows.map { it.request.schedule }.distinct().singleOrNull()
  // Start now is an action of its own; the submenu only holds later times.
  for (option in startTimeOptions(context.now, context.zone).drop(1)) {
    item(
      label = option.label,
      onClick = { runner.reschedule(rows, option.schedule) },
      checked = option.schedule == current,
    )
  }
  item(label = "Pick date & time…", onClick = { runner.requestStartTime(rows) })
  if (current != null && current != DownloadSchedule.Immediate) {
    divider()
    item(label = "Clear", onClick = { runner.reschedule(rows, DownloadSchedule.Immediate) })
  }
}

/** Adds the [devices] [rows] can be sent to; offline ones are listed but disabled. */
internal fun KetchMenuScope.sendEntries(
  rows: List<TaskRow>,
  runner: RowActionRunner,
  devices: List<SendTarget>,
) {
  for (device in devices) {
    item(
      label = device.option.name,
      onClick = { runner.sendTo(rows, device.entry) },
      caption = deviceOptionCaption(device.option),
      enabled = device.option.health.isOnline,
    )
  }
}

/** The devices other than those of [rows], with their health. */
internal fun sendTargets(instances: List<InstanceEntry>, rows: List<TaskRow>): List<SendTarget> {
  val here = rows.mapTo(mutableSetOf()) { it.key.deviceId }
  return instances.filter { it.deviceId !in here }.map { entry ->
    val health = when (entry) {
      is RemoteInstance -> entry.connectionState.value.toDeviceHealth()
      else -> DeviceHealth.Local()
    }
    val name = if (entry is EmbeddedInstance) localDeviceNoun() else entry.label
    SendTarget(entry, DeviceOption(entry.deviceId, name, health))
  }
}

/**
 * The download Urgent would pause to start [rows]: the lowest-priority one running on their
 * device, when every slot is taken; `null` when a slot is free or nothing could be paused.
 */
internal fun urgentVictim(rows: List<TaskRow>, runner: RowActionRunner): String? {
  if (rows.all { it.state is DownloadState.Downloading }) return null
  val deviceId = rows.first().key.deviceId
  val keys = rows.mapTo(mutableSetOf()) { it.key }
  val running = runner.state.taskList.rows.value.filter {
    it.key.deviceId == deviceId && it.state is DownloadState.Downloading
  }
  val slots = runner.state.instanceSettings.download?.maxConcurrentDownloads
  if (slots != null && running.size < slots) return null
  return running.filter { it.key !in keys && it.request.priority < DownloadPriority.URGENT }
    .minByOrNull { it.request.priority.ordinal }?.name
}

/** Menu label of [action] on one row; Show in folder takes the platform's [revealLabel]. */
internal fun rowActionLabel(action: RowAction, revealLabel: String?): String =
  if (action == RowAction.ShowInFolder) revealLabel ?: action.label else action.label

/**
 * Menu label of [action] over [count] selected rows, such as "Pause 3 downloads" or "Copy 3
 * links"; submenus keep their plain label.
 */
internal fun batchLabel(action: RowAction, count: Int, revealLabel: String?): String {
  val what = downloads(count)
  return when (action) {
    RowAction.Pause -> "Pause $what"
    RowAction.Resume -> "Resume $what"
    RowAction.StartNow -> "Start $what now"
    RowAction.Retry -> "Retry $what"
    RowAction.Open -> if (count == 1) "Open 1 file" else "Open $count files"
    RowAction.ShowInFolder -> "${revealLabel ?: action.label} ($count)"
    RowAction.CopyLink -> if (count == 1) "Copy 1 link" else "Copy $count links"
    RowAction.CopyPath -> if (count == 1) "Copy 1 file path" else "Copy $count file paths"
    RowAction.DownloadAgain -> {
      if (count == 1) "Download 1 file again" else "Download $count files again"
    }
    RowAction.StopAndDiscard -> "Discard progress of $what…"
    RowAction.Remove -> "Remove $what from list"
    RowAction.RemoveAndTrash, RowAction.RemoveAndDelete -> {
      if (count == 1) "Remove 1 download and its file…" else "Remove $what and their files…"
    }
    else -> action.label
  }
}

/** The command whose shortcut runs [this] action from the list, for hints; `null` if none. */
internal val RowAction.command: KetchCommand?
  get() = when (this) {
    RowAction.Pause, RowAction.Resume -> KetchCommands.TogglePause
    RowAction.Open -> KetchCommands.Open
    RowAction.ShowInFolder -> KetchCommands.Reveal
    RowAction.CopyLink -> KetchCommands.CopyLink
    RowAction.CopyPath -> KetchCommands.CopyPath
    RowAction.Retry -> KetchCommands.Retry
    RowAction.Details -> KetchCommands.ToggleInspector
    RowAction.Remove -> KetchCommands.Remove
    RowAction.RemoveAndTrash, RowAction.RemoveAndDelete -> KetchCommands.RemoveAndTrash
    else -> null
  }

/** Glyph of [this] action on buttons and in menus. */
internal val RowAction.icon: KetchIcon
  get() = when (this) {
    RowAction.Pause -> KetchIcon.Pause
    RowAction.Resume, RowAction.StartNow -> KetchIcon.Play
    RowAction.Reconnect,
    RowAction.Retry,
    is RowAction.RetryWithConnections,
    RowAction.DownloadAgain -> KetchIcon.Retry
    RowAction.SpeedLimit -> KetchIcon.Speed
    RowAction.Connections -> KetchIcon.Lanes
    RowAction.Priority -> KetchIcon.Bolt
    RowAction.StartLater -> KetchIcon.Scheduled
    RowAction.SendTo -> KetchIcon.Devices
    RowAction.CopyLink, RowAction.EditLink -> KetchIcon.Link
    RowAction.Details -> KetchIcon.Info
    RowAction.Open, RowAction.OpenSourcePage -> KetchIcon.Open
    RowAction.ShowInFolder -> KetchIcon.Reveal
    RowAction.CopyPath, RowAction.CopyError, RowAction.CopyDetails -> KetchIcon.Copy
    RowAction.RetryWithOptions, RowAction.EnterCredentials -> KetchIcon.Settings
    RowAction.FindAnotherSource -> KetchIcon.Discover
    RowAction.StopAndDiscard -> KetchIcon.Stop
    RowAction.Remove, RowAction.RemoveAndTrash, RowAction.RemoveAndDelete -> KetchIcon.Trash
  }
