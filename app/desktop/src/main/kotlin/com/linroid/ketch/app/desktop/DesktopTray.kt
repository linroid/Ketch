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
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.platform.rememberFileActions
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.PulseState
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.TaskKey
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
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

private val isMac = System.getProperty("os.name").startsWith("Mac")

/**
 * What the tray icon, the menu bar, the Dock and the taskbar show: the Pulse of the active scope,
 * taken at most once a second, and the failures the user has not looked at yet.
 *
 * Create it with [rememberDesktopStatus] and share it between [KetchTray], [KetchMenuBar] and
 * [TaskbarFeedback].
 */
@Stable
class DesktopStatus internal constructor() {
  /** Pulse of the active scope. */
  var pulse: PulseState by mutableStateOf(PulseState())
    private set

  /** Failed tasks that appeared since the Failed tab was last viewed. */
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
   * Takes [pulse] as the current status. While [viewingFailures], failures count as seen. A
   * Pulse without devices is not loaded yet and leaves the failures alone.
   */
  internal fun update(pulse: PulseState, viewingFailures: Boolean) {
    this.pulse = pulse
    if (pulse.devices.isEmpty()) return
    if (watch.update(pulse.failures, viewingFailures) > 0) failures.tryEmit(Unit)
    unseenFailures = watch.unseen
  }
}

/**
 * Creates the [DesktopStatus] of [controller], following [pulse].
 *
 * @param pulse the Pulse of the active scope, such as the state of the controller's Pulse model.
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
    pulse.throttleLatest(STATUS_INTERVAL).collect { status.update(it, currentViewing) }
  }
  LaunchedEffect(status, viewing) {
    if (viewing) status.update(status.pulse, viewingFailures = true)
  }
  return status
}

/**
 * The tray icon (the menu bar extra on macOS) and its menu: the status sentence, adding
 * downloads, pausing and resuming everything, the speed mode, the last finished downloads, and
 * the window, Settings and Quit. Clicking the icon on Windows and Linux shows the window.
 *
 * The icon is the sail with a ring for the overall progress and a dot for failures not seen yet,
 * dimmed while everything is paused. On macOS it is a template image, which the menu bar tints.
 * Nothing shows where the system has no tray.
 *
 * @param status the Pulse and unseen failures to show.
 * @param actions shows the window and quits the app.
 * @param speedMode switches the active device's speed mode; `null` leaves out the Speed menu.
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
  val pulse = status.pulse
  val entries = trayMenu(TrayContext(pulse, speed, recent))
  Tray(
    icon = rememberTrayIcon(pulse, status.unseenFailures),
    state = state,
    tooltip = trayTooltip(pulse),
    onAction = actions.showWindow,
  ) {
    MenuEntries(entries, KeyboardPlatform.current, commands::perform)
  }
}

/**
 * A download that finished on this computer, listed under Recent.
 *
 * @property name name shown in the menu.
 * @property path where the file was saved.
 */
internal data class RecentDownload(val name: String, val path: String)

/**
 * The speed mode of the active device, for the Speed menu.
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
 * What the tray menu reflects.
 *
 * @property speed the speed mode; `null` leaves out the Speed menu.
 * @property recent finished downloads, newest first.
 * @property now the time the status sentence's clock times are relative to.
 */
internal data class TrayContext(
  val pulse: PulseState,
  val speed: TraySpeed?,
  val recent: List<RecentDownload>,
  val now: Instant = Clock.System.now(),
)

/** The tray menu in [context]; the Devices section joins it with All devices. */
internal fun trayMenu(context: TrayContext): List<MenuEntry> = buildList {
  fun command(command: KetchCommand, enabled: Boolean = true) =
    MenuEntry.Item(MenuAction.Run(command), command.label, enabled)

  val counts = context.pulse.counts
  add(MenuEntry.Header(context.pulse.sentence(now = context.now)))
  add(command(KetchCommands.Add))
  add(command(KetchCommands.AddClipboardLink))
  add(command(KetchCommands.OpenTorrent))
  add(MenuEntry.Separator)
  add(command(KetchCommands.PauseAll, enabled = counts.downloading + counts.waiting > 0))
  add(command(KetchCommands.ResumeAll, enabled = counts.paused > 0))
  context.speed?.let { add(speedMenu(it)) }
  add(MenuEntry.Separator)
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

private fun speedMenu(speed: TraySpeed): MenuEntry.Submenu {
  fun choice(mode: SpeedLimitMode, label: String, enabled: Boolean = true) = MenuEntry.Item(
    action = MenuAction.SetSpeedMode(mode),
    label = label,
    enabled = enabled,
    checked = speed.mode == mode,
  )
  val slowLane = "${speedModeName(SpeedLimitMode.SlowLane)} · ${formatSpeed(speed.slowLane)}"
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
internal fun trayTooltip(pulse: PulseState, now: Instant = Clock.System.now()): String {
  val downloading = pulse.counts.downloading
  val summary = if (downloading > 0) {
    "↓ ${formatBytes(pulse.totalSpeed)}/s · $downloading active"
  } else {
    pulse.sentence(now = now)
  }
  return "Ketch — $summary"
}

private fun formatSpeed(limit: SpeedLimit): String = "${formatBytes(limit.bytesPerSecond)}/s"

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
  val counts = pulse.counts
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
