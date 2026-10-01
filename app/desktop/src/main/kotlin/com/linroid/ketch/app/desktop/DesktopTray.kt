package com.linroid.ketch.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.window.ApplicationScope
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.TrayState
import androidx.compose.ui.window.isTraySupported
import androidx.compose.ui.window.rememberTrayState
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.SpeedLimitPickerPresets
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.platform.rememberFileActions
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.DevicePulse
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.LocalClock
import com.linroid.ketch.app.state.PulseCounts
import com.linroid.ketch.app.state.PulseState
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.state.isSlowLane
import com.linroid.ketch.app.theme.darkKetchColors
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.app.util.formatBytes
import com.linroid.ketch.config.SpeedLimitMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transform
import kotlin.math.roundToInt
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val isMac = System.getProperty("os.name").startsWith("Mac")

/**
 * What the tray icon, the menu bar, the Dock and the taskbar show, taken at most once a second:
 * the Pulse of the window's scope, the Pulse of every device the app keeps connected, what each
 * device is doing, and the failures the user has not looked at yet.
 *
 * Create it with [rememberDesktopStatus] and share it between [KetchTray], [KetchMenuBar] and
 * [TaskbarFeedback].
 */
@Stable
class DesktopStatus internal constructor() {
  /** Pulse of the devices the main window shows. */
  var pulse: PulseState by mutableStateOf(PulseState())
    private set

  /**
   * Pulse of every device the app keeps connected (this computer, the active device and the
   * watched ones), which the tray and the Dock sum up whichever device the window shows.
   */
  var fleet: PulseState by mutableStateOf(PulseState())
    private set

  /** Every configured device and what it is doing, for the tray's Devices menu. */
  var devices: List<DevicePresence> by mutableStateOf(emptyList())
    private set

  /** Failed tasks on [fleet] that appeared since the Failed tab last showed their device. */
  var unseenFailures: Int by mutableStateOf(0)
    private set

  /** Whether the main window has focus. */
  internal var windowFocused: Boolean = false

  private val watch = FailureWatch()
  private val failures = MutableSharedFlow<Unit>(
    extraBufferCapacity = 1,
    onBufferOverflow = BufferOverflow.DROP_OLDEST,
  )

  /** Emits when tasks fail, for the Dock's request for attention. */
  internal val newFailures: SharedFlow<Unit> = failures.asSharedFlow()

  /**
   * Takes [pulse], [devices] and [fleet] as the current status. While [viewingFailures], the
   * failures of [pulse]'s devices count as seen.
   */
  internal fun update(
    pulse: PulseState,
    viewingFailures: Boolean,
    devices: List<DevicePresence> = this.devices,
    fleet: PulseState = pulse,
  ) {
    this.pulse = pulse
    this.fleet = fleet
    this.devices = devices
    val viewed = if (viewingFailures) pulse.devices.mapTo(HashSet()) { it.deviceId } else emptySet()
    if (watch.update(fleet.devices, viewed) > 0) failures.tryEmit(Unit)
    unseenFailures = watch.unseen
  }
}

/**
 * Creates the [DesktopStatus] of [controller], following [pulse] and the controller's devices.
 *
 * @param pulse the Pulse of the window's scope, such as the state of the controller's Pulse
 *   model.
 * @param windowFocused whether the main window has focus; failures count as seen while it shows
 *   the Failed tab.
 */
@Composable
fun rememberDesktopStatus(
  controller: AppController,
  pulse: Flow<PulseState>,
  windowFocused: Boolean,
): DesktopStatus {
  val status = remember(controller) { DesktopStatus() }
  val viewing = windowFocused && controller.state.statusFilter == StatusFilter.Failed
  val currentViewing by rememberUpdatedState(viewing)
  SideEffect { status.windowFocused = windowFocused }
  LaunchedEffect(status, pulse) {
    val localMode = controller.speedMode?.mode ?: flowOf(SpeedMode.Full)
    combine(
      pulse,
      controller.pulse.devices,
      controller.instanceManager.presence,
      localMode,
      ::StatusReading,
    ).throttleLatest(STATUS_INTERVAL).collect { reading ->
      val fleet = fleetPulse(reading.presence, reading.pulses, reading.localMode)
      status.update(reading.pulse, currentViewing, reading.presence, fleet)
    }
  }
  LaunchedEffect(status, viewing) {
    if (viewing) status.update(status.pulse, viewingFailures = true, fleet = status.fleet)
  }
  return status
}

private class StatusReading(
  val pulse: PulseState,
  val pulses: List<DevicePulse>,
  val presence: List<DevicePresence>,
  val localMode: SpeedMode,
)

/**
 * The Pulse of every device the app keeps connected, in the order of [presence]: each as
 * [pulses] knows it, else as its presence reports it, without the byte totals behind the
 * progress ring and the finish time. Until the presence is in, the online devices of [pulses].
 * [localMode] is this computer's speed mode, the one the status sentence names.
 */
internal fun fleetPulse(
  presence: List<DevicePresence>,
  pulses: List<DevicePulse>,
  localMode: SpeedMode,
): PulseState {
  val known = pulses.associateBy { it.deviceId }
  val devices = if (presence.isEmpty()) {
    pulses.filter { it.health.isOnline }
  } else {
    presence.filter { it.connected }.map { known[it.deviceId] ?: it.toPulse() }
  }
  return PulseState(devices = devices, allDevices = devices.size > 1, mode = localMode)
}

private fun DevicePresence.toPulse(): DevicePulse = DevicePulse(
  deviceId = deviceId,
  name = name,
  health = health,
  counts = counts,
  failures = failures,
  speed = speed,
  cap = cap,
  downloadedBytes = 0,
  sizeBytes = 0,
  sizesKnown = false,
  pendingBytes = 0,
  disk = disk,
  history = history,
)

/**
 * The tray icon (the menu bar extra on macOS) and its menu: the status sentence, adding
 * downloads, pausing and resuming everything, the speed mode, each device with what it is doing
 * and its own actions, the last finished downloads, and the window, Settings and Quit. Clicking
 * the icon on Windows and Linux shows the window.
 *
 * The icon, its tooltip and the status sentence sum up every device the app keeps connected:
 * the sail with a ring for the overall progress and a dot for failures not seen yet, dimmed
 * while everything is paused. On macOS it is a template image, which the menu bar tints.
 * Nothing shows where the system has no tray.
 *
 * @param status the Pulse, devices and unseen failures to show.
 * @param actions shows the window and quits the app.
 * @param speedMode switches the active device's speed mode; `null` leaves out the Speed menu.
 *   This computer's own Speed menu, under Devices, follows the controller's speed mode.
 * @param state sends notifications from the icon, see [TrayNotifier].
 */
@Composable
fun ApplicationScope.KetchTray(
  controller: AppController,
  status: DesktopStatus,
  actions: DesktopActions,
  speedMode: SpeedModeController? = null,
  state: TrayState = rememberTrayState(),
) {
  if (!isTraySupported) return
  // Read once, when the first tray icon is created.
  remember { if (isMac) System.setProperty("apple.awt.enableTemplateImages", "true") }
  val files = rememberFileActions()
  val clipboard = rememberSystemClipboard()
  val commands = remember(controller, actions, speedMode, files, clipboard) {
    DesktopCommands(controller, actions, speedMode, files, clipboard)
  }
  val recent by remember(controller) { recentDownloads(controller) }.collectAsState(emptyList())
  val speed = speedMode?.let { traySpeed(it) }
  val localSpeed = controller.speedMode?.let { traySpeed(it) }
  val devices = status.devices.map { trayDevice(controller.state, it, localSpeed) }
  val fleet = status.fleet
  val now = LocalClock.current.now()
  val entries = trayMenu(TrayContext(fleet, speed, recent, now, devices))
  Tray(
    icon = rememberTrayIcon(fleet, status.unseenFailures),
    state = state,
    tooltip = trayTooltip(fleet, now),
    onAction = actions.showWindow,
  ) {
    MenuEntries(entries, KeyboardPlatform.current, commands::perform)
  }
}

// This computer's Speed menu switches its speed mode; a device without one gets its speed limit,
// as the app last read it, else as the device last reported it.
private fun trayDevice(
  state: AppState,
  device: DevicePresence,
  localSpeed: TraySpeed?,
): TrayDevice {
  if (device.entry is EmbeddedInstance && localSpeed != null) {
    return TrayDevice(device, speed = localSpeed)
  }
  val limit = state.settingsFor(device.entry).download?.speedLimit
    ?: device.status?.config?.speedLimit
  return TrayDevice(device, limit = limit)
}

/**
 * A download that finished on this computer, listed under Recent.
 *
 * @property name name shown in the menu.
 * @property path where the file was saved.
 */
internal data class RecentDownload(val name: String, val path: String)

/**
 * A speed mode, for a Speed menu.
 *
 * @property mode the mode chosen.
 * @property slowLane speed of the slow lane.
 * @property hasRules whether Auto has rules to follow.
 */
internal data class TraySpeed(
  val mode: SpeedLimitMode,
  val slowLane: SpeedLimit,
  val hasRules: Boolean,
)

/**
 * A device under the tray's Devices menu.
 *
 * @property presence what the device is doing.
 * @property speed its speed mode, for its Speed menu; `null` for a device without one.
 * @property limit for a device without a speed mode, its download speed limit, which its Speed
 *   menu picks; `null` while unknown, which leaves the menu out.
 */
internal data class TrayDevice(
  val presence: DevicePresence,
  val speed: TraySpeed? = null,
  val limit: SpeedLimit? = null,
)

/**
 * What the tray menu reflects.
 *
 * @property pulse every device the app keeps connected, which the status sentence, Pause all
 *   and Resume all sum up.
 * @property speed the active device's speed mode; `null` leaves out the Speed menu.
 * @property recent finished downloads, newest first.
 * @property now the time the status sentence's clock times are relative to.
 * @property devices every configured device, in the order of the app's devices.
 */
internal data class TrayContext(
  val pulse: PulseState,
  val speed: TraySpeed?,
  val recent: List<RecentDownload>,
  val now: Instant,
  val devices: List<TrayDevice> = emptyList(),
)

/** The tray menu in [context]. */
internal fun trayMenu(context: TrayContext): List<MenuEntry> = buildList {
  fun command(command: KetchCommand) = MenuEntry.Item(MenuAction.Run(command), command.label)

  val counts = context.pulse.onlineCounts
  val targets = context.pulse.devices.filter { it.health.isOnline }.map { it.deviceId }
  add(MenuEntry.Header(context.pulse.sentence(now = context.now)))
  add(command(KetchCommands.Add))
  add(command(KetchCommands.AddClipboardLink))
  add(command(KetchCommands.OpenTorrent))
  add(MenuEntry.Separator)
  add(pauseAll(targets, counts))
  add(resumeAll(targets, counts))
  context.speed?.let { add(speedMenu(it)) }
  add(MenuEntry.Separator)
  add(MenuEntry.Submenu("Devices", devicesMenu(context.devices)))
  add(
    MenuEntry.Submenu(
      label = "Recent",
      entries = context.recent.map {
        MenuEntry.Item(MenuAction.OpenFile(it.path, it.name), it.name)
      },
      enabled = context.recent.isNotEmpty(),
    ),
  )
  add(MenuEntry.Separator)
  add(MenuEntry.Item(MenuAction.ShowWindow, "Show Ketch"))
  add(command(KetchCommands.Settings))
  add(command(KetchCommands.Quit))
}

/**
 * Task counts of the online devices in this Pulse; a device that is not online may still list
 * tasks as downloading from when it last was.
 */
internal val PulseState.onlineCounts: PulseCounts
  get() = devices.filter { it.health.isOnline }
    .fold(PulseCounts()) { sum, device -> sum + device.counts }

/**
 * What [device] is doing, after its name in the Devices menu: "6.4 MB/s · 2 active",
 * "2 waiting", "3 paused" or "Idle", then "Slow lane" and "1 failed" when they apply; or why it
 * cannot be reached.
 */
internal fun trayDeviceStatus(device: DevicePresence): String {
  val health = device.health
  return when {
    health == DeviceHealth.Unauthorized -> "Needs token"
    !device.connected -> "Not connected"
    health is DeviceHealth.Offline -> "Offline"
    health == DeviceHealth.Connecting -> "Connecting"
    else -> {
      val counts = device.counts
      val parts = buildList {
        when {
          counts.downloading > 0 -> {
            add(formatSpeed(device.speed))
            add("${counts.downloading} active")
          }
          counts.waiting > 0 -> add("${counts.waiting} waiting")
          counts.paused > 0 -> add("${counts.paused} paused")
        }
        if (device.speedMode.isSlowLane && counts.downloading + counts.waiting > 0) {
          add(speedModeName(SpeedLimitMode.SlowLane))
        }
        if (device.failures > 0) add("${device.failures} failed")
      }
      parts.joinToString(" · ").ifEmpty { "Idle" }
    }
  }
}

private fun devicesMenu(devices: List<TrayDevice>): List<MenuEntry> = buildList {
  devices.forEach { add(deviceMenu(it)) }
  if (devices.isNotEmpty()) add(MenuEntry.Separator)
  add(MenuEntry.Item(MenuAction.AddDevice, ADD_DEVICE))
}

// Show, then what the device's state allows: its downloads' actions while it is online, Retry
// now while it is offline, and a new token once it rejected its own.
private fun deviceMenu(device: TrayDevice): MenuEntry.Submenu {
  val presence = device.presence
  val id = presence.deviceId
  val health = presence.health
  val entries = buildList {
    add(MenuEntry.Item(MenuAction.ShowDevice(id), "Show"))
    when {
      health == DeviceHealth.Unauthorized -> {
        add(MenuEntry.Item(MenuAction.EnterToken(id), "Enter token…"))
      }
      !presence.connected -> Unit
      health is DeviceHealth.Offline -> add(MenuEntry.Item(MenuAction.Reconnect(id), "Retry now"))
      health.isOnline -> addAll(onlineEntries(device))
    }
    if (presence.entry is RemoteInstance) {
      add(MenuEntry.Separator)
      add(
        MenuEntry.Item(
          action = MenuAction.StayConnected(id, watch = !presence.watched),
          label = "Stay connected",
          checked = presence.watched,
        ),
      )
    }
  }
  return MenuEntry.Submenu("${presence.name} — ${trayDeviceStatus(presence)}", entries)
}

private fun onlineEntries(device: TrayDevice): List<MenuEntry> = buildList {
  val presence = device.presence
  val id = presence.deviceId
  add(MenuEntry.Separator)
  add(pauseAll(listOf(id), presence.counts))
  add(resumeAll(listOf(id), presence.counts))
  if (presence.failures > 0) {
    add(MenuEntry.Item(MenuAction.RetryFailed(listOf(id)), "Retry ${presence.failures} failed"))
  }
  device.speed?.let { add(speedMenu(it)) }
  device.limit?.let { add(limitMenu(id, it)) }
  add(MenuEntry.Separator)
  add(MenuEntry.Item(MenuAction.AddClipboardLink(id), "Add clipboard link here"))
}

private fun pauseAll(deviceIds: List<String>, counts: PulseCounts) = MenuEntry.Item(
  action = MenuAction.PauseAll(deviceIds),
  label = KetchCommands.PauseAll.label,
  enabled = counts.downloading + counts.waiting > 0,
)

private fun resumeAll(deviceIds: List<String>, counts: PulseCounts) = MenuEntry.Item(
  action = MenuAction.ResumeAll(deviceIds),
  label = KetchCommands.ResumeAll.label,
  enabled = counts.paused > 0,
)

// The speed limits the app's picker offers, and the device's own when it is none of them.
private fun limitMenu(deviceId: String, limit: SpeedLimit): MenuEntry.Submenu {
  val choices = (SpeedLimitPickerPresets + limit).distinct().sortedBy { it.bytesPerSecond }
  return MenuEntry.Submenu(
    label = "Speed",
    entries = choices.map { choice ->
      MenuEntry.Item(
        action = MenuAction.SetSpeedLimit(deviceId, choice),
        label = formatSpeedLimit(choice),
        checked = choice == limit,
      )
    },
  )
}

private fun speedMenu(speed: TraySpeed): MenuEntry.Submenu {
  fun choice(mode: SpeedLimitMode, label: String, enabled: Boolean = true) = MenuEntry.Item(
    action = MenuAction.SetSpeedMode(mode),
    label = label,
    enabled = enabled,
    checked = speed.mode == mode,
  )
  val slowLane = "${speedModeName(SpeedLimitMode.SlowLane)} · ${formatSpeedLimit(speed.slowLane)}"
  return MenuEntry.Submenu(
    label = "Speed",
    entries = listOf(
      choice(SpeedLimitMode.Full, speedModeName(SpeedLimitMode.Full)),
      choice(SpeedLimitMode.SlowLane, slowLane),
      choice(
        SpeedLimitMode.Auto,
        speedModeName(SpeedLimitMode.Auto),
        enabled = speed.hasRules || speed.mode == SpeedLimitMode.Auto,
      ),
    ),
  )
}

/** "Ketch — ↓ 4.2 MB/s · 3 active" while downloading, else "Ketch — " and the sentence. */
internal fun trayTooltip(pulse: PulseState, now: Instant): String {
  val downloading = pulse.onlineCounts.downloading
  val summary = if (downloading > 0) {
    "↓ ${formatSpeed(pulse.totalSpeed)} · $downloading active"
  } else {
    pulse.sentence(now = now)
  }
  return "Ketch — $summary"
}

private fun formatSpeed(bytesPerSecond: Long): String = "${formatBytes(bytesPerSecond)}/s"

@Composable
private fun traySpeed(speedMode: SpeedModeController): TraySpeed {
  val settings by speedMode.settings.collectAsState()
  val peak by speedMode.observedPeak.collectAsState()
  // Without a speed of its own, the slow lane follows the observed peak.
  val slowLane = remember(settings, peak) { speedMode.slowLaneSpeed }
  return TraySpeed(settings.mode, slowLane, settings.rules.isNotEmpty())
}

// Completed downloads of the embedded device, newest first, without the ones a pending removal
// hides. Only a change of phase re-reads the list, not every progress update.
@OptIn(ExperimentalCoroutinesApi::class)
private fun recentDownloads(controller: AppController): Flow<List<RecentDownload>> {
  val embedded = controller.instanceManager.embedded ?: return flowOf(emptyList())
  val finished = embedded.tasks.flatMapLatest { tasks ->
    if (tasks.isEmpty()) return@flatMapLatest flowOf(emptyList())
    val states = tasks.map { task ->
      task.state.map { it as? DownloadState.Completed }.distinctUntilChanged().map { task to it }
    }
    combine(states) { it.toList() }
  }
  return combine(finished, controller.state.pendingOps.hidden) { pairs, hidden ->
    pairs
      .filter { (task, done) -> done != null && TaskKey(LOCAL_DEVICE_ID, task.taskId) !in hidden }
      .sortedByDescending { (task, _) -> task.createdAt }
      .take(RECENT_LIMIT)
      .map { (task, done) ->
        val completed = checkNotNull(done)
        RecentDownload(displayName(task.request, completed), completed.outputPath)
      }
  }
}

@Composable
private fun rememberTrayIcon(pulse: PulseState, unseenFailures: Int): Painter {
  val sail = rememberVectorPainter(KetchIcon.Sail.imageVector)
  val counts = pulse.onlineCounts
  val active = counts.downloading > 0
  // Steps keep the icon from being redrawn for changes too small to see.
  val progress = pulse.progress?.takeIf { active }
    ?.let { (it * PROGRESS_STEPS).roundToInt() / PROGRESS_STEPS.toFloat() }
  val failed = unseenFailures > 0
  val dimmed = !active && counts.waiting == 0 && counts.paused > 0
  return remember(sail, active, progress, failed, dimmed) {
    TrayIconPainter(sail, active, progress, failed, dimmed, trayIconColors)
  }
}

private class TrayIconColors(val glyph: Color, val ring: Color, val failure: Color)

// A template image only keeps the alpha, which macOS tints for the menu bar; elsewhere the icon
// sits on the dark taskbars and panels most systems use.
private val trayIconColors: TrayIconColors = if (isMac) {
  TrayIconColors(Color.Black, Color.Black, Color.Black)
} else {
  val colors = darkKetchColors()
  TrayIconColors(colors.textPrimary, colors.accent, colors.status.failed.color)
}

private class TrayIconPainter(
  private val sail: Painter,
  private val active: Boolean,
  private val progress: Float?,
  private val failed: Boolean,
  private val dimmed: Boolean,
  private val colors: TrayIconColors,
) : Painter() {
  override val intrinsicSize: Size get() = Size.Unspecified

  override fun DrawScope.onDraw() {
    val alpha = if (dimmed) DIMMED_ALPHA else 1f
    val extent = size.minDimension
    val glyph = if (active) extent * RING_GLYPH_SCALE else extent
    translate((size.width - glyph) / 2, (size.height - glyph) / 2) {
      with(sail) { draw(Size(glyph, glyph), alpha, ColorFilter.tint(colors.glyph)) }
    }
    if (active) {
      val stroke = extent * RING_STROKE_SCALE
      val topLeft = Offset(stroke / 2, stroke / 2)
      val arc = Size(size.width - stroke, size.height - stroke)
      drawArc(
        color = colors.glyph,
        startAngle = 0f,
        sweepAngle = FULL_CIRCLE,
        useCenter = false,
        topLeft = topLeft,
        size = arc,
        alpha = alpha * RING_TRACK_ALPHA,
        style = Stroke(stroke),
      )
      if (progress != null && progress > 0f) {
        drawArc(
          color = colors.ring,
          startAngle = RING_START_ANGLE,
          sweepAngle = FULL_CIRCLE * progress,
          useCenter = false,
          topLeft = topLeft,
          size = arc,
          alpha = alpha,
          style = Stroke(stroke, cap = StrokeCap.Round),
        )
      }
    }
    if (failed) {
      val radius = extent * DOT_SCALE / 2
      val center = Offset(size.width - radius, radius)
      // A gap around the dot keeps it apart from the sail and the ring.
      drawCircle(Color.Transparent, radius * DOT_GAP_SCALE, center, blendMode = BlendMode.Clear)
      drawCircle(colors.failure, radius, center)
    }
  }
}

// Emits the first value at once, then the latest value at most once per period.
private fun <T> Flow<T>.throttleLatest(period: Duration): Flow<T> = conflate().transform {
  emit(it)
  delay(period)
}

private val STATUS_INTERVAL = 1.seconds
private const val RECENT_LIMIT = 5
private const val PROGRESS_STEPS = 32
private const val FULL_CIRCLE = 360f
private const val RING_START_ANGLE = -90f
private const val RING_GLYPH_SCALE = 0.62f
private const val RING_STROKE_SCALE = 0.11f
private const val RING_TRACK_ALPHA = 0.3f
private const val DOT_SCALE = 0.25f
private const val DOT_GAP_SCALE = 1.5f
private const val DIMMED_ALPHA = 0.45f
