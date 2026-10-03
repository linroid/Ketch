package com.linroid.ketch.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.graphics.toArgb
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.state.DevicePulse
import com.linroid.ketch.app.state.PulseState
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.theme.lightKetchColors
import com.linroid.ketch.config.DockBadgeMode
import java.awt.Color
import java.awt.Font
import java.awt.RenderingHints
import java.awt.Taskbar
import java.awt.Window
import java.awt.image.BufferedImage
import kotlin.math.roundToInt

/**
 * Shows the downloads of every device the app keeps connected on the Dock icon (macOS) or the
 * taskbar button (Windows): a badge with the active count or "!" for unseen failures, the overall
 * progress, and on macOS one bounce for each failure while the window is unfocused. Linux shows
 * nothing. Every call checks that the system supports it.
 *
 * @param status the [fleet][DesktopStatus.fleet] and unseen failures to show.
 * @param badgeMode what the badge shows, from the `[desktop]` settings.
 * @param window the main window, which Windows draws the progress and the badge on; `null` while
 *   it has none.
 */
@Composable
fun TaskbarFeedback(status: DesktopStatus, badgeMode: DockBadgeMode, window: Window? = null) {
  val taskbar = remember { TaskbarApplier.create() } ?: return
  val model = taskbarModel(status.fleet, status.unseenFailures, badgeMode)
  LaunchedEffect(taskbar, model, window) { taskbar.apply(model, window) }
  LaunchedEffect(taskbar, status) {
    status.newFailures.collect { if (!status.windowFocused) taskbar.requestAttention() }
  }
  val currentWindow by rememberUpdatedState(window)
  DisposableEffect(taskbar) {
    onDispose { taskbar.apply(TaskbarModel.Idle, currentWindow) }
  }
}

/** Progress state of the Windows taskbar button. */
internal enum class TaskbarProgress(val awt: Taskbar.State) {
  /** Nothing to show. */
  Off(Taskbar.State.OFF),

  /** Downloading. */
  Normal(Taskbar.State.NORMAL),

  /** Everything left is paused. */
  Paused(Taskbar.State.PAUSED),

  /** A download failed and the user has not seen it. */
  Error(Taskbar.State.ERROR),
}

/**
 * What the Dock icon or the taskbar button shows.
 *
 * @property badge badge text, such as "3" or "!"; `null` for none.
 * @property progress overall progress from 0 to 100, or -1 to hide it.
 * @property state progress state of the Windows taskbar button.
 */
internal data class TaskbarModel(
  val badge: String?,
  val progress: Int,
  val state: TaskbarProgress,
) {
  /**
   * Progress of the Windows taskbar button. Windows tints the bar's value for [state], so a
   * paused or failed state with no progress to show fills the bar instead of leaving it empty.
   */
  val windowProgress: Int
    get() {
      val blocked = state == TaskbarProgress.Paused || state == TaskbarProgress.Error
      return if (blocked && progress < 0) FULL_PROGRESS else progress
    }

  companion object {
    /** Nothing shown. */
    val Idle: TaskbarModel = TaskbarModel(badge = null, progress = -1, state = TaskbarProgress.Off)
  }
}

/** What the Dock icon or taskbar button shows for [pulse] with [unseenFailures], per [mode]. */
internal fun taskbarModel(
  pulse: PulseState,
  unseenFailures: Int,
  mode: DockBadgeMode,
): TaskbarModel {
  val counts = pulse.onlineCounts
  val active = counts.downloading + counts.waiting
  val failed = unseenFailures > 0
  val badge = when {
    mode == DockBadgeMode.Off -> null
    failed -> "!"
    mode == DockBadgeMode.ActiveCount && active > 0 -> active.toString()
    else -> null
  }
  val progress = pulse.progress?.takeIf { counts.downloading > 0 }
    ?.let { (it * FULL_PROGRESS).roundToInt().coerceIn(0, FULL_PROGRESS) }
    ?: -1
  val state = when {
    failed -> TaskbarProgress.Error
    counts.downloading > 0 -> TaskbarProgress.Normal
    active == 0 && counts.paused > 0 -> TaskbarProgress.Paused
    else -> TaskbarProgress.Off
  }
  return TaskbarModel(badge, progress, state)
}

/**
 * Counts the failures the user has not seen, device by device.
 *
 * A device's first count while it is online is its baseline: those failures are unseen but not
 * new. Failed tasks that arrive along with new tasks, as when the engine restores its tasks or a
 * remote device's list loads after it connects, are unseen but not new either; only tasks that
 * turn failed are. An offline device keeps its last count, and a count that drops forgets the
 * failures that went away.
 */
internal class FailureWatch {
  private val known = HashMap<String, DeviceFailures>()

  /** Failures not seen yet on the devices of the last update. */
  var unseen: Int = 0
    private set

  /**
   * Takes the failures of [devices]; those of the devices in [viewed] count as seen.
   *
   * @return how many tasks turned failed since the last update.
   */
  fun update(devices: List<DevicePulse>, viewed: Set<String>): Int {
    var new = 0
    for (device in devices) {
      val previous = known[device.deviceId]
      val tasks = device.counts.count(StatusFilter.All)
      val failures = device.failures
      val next = when {
        !device.health.isOnline -> previous ?: continue
        previous == null -> DeviceFailures(tasks, failures, unseen = failures)
        else -> {
          val more = (failures - previous.failures).coerceAtLeast(0)
          val arrived = (tasks - previous.tasks).coerceAtLeast(0)
          new += (more - arrived).coerceAtLeast(0)
          DeviceFailures(tasks, failures, minOf(previous.unseen + more, failures))
        }
      }
      known[device.deviceId] = if (device.deviceId in viewed) next.copy(unseen = 0) else next
    }
    unseen = devices.sumOf { known[it.deviceId]?.unseen ?: 0 }
    return new
  }

  private data class DeviceFailures(val tasks: Int, val failures: Int, val unseen: Int)
}

private class TaskbarApplier(private val taskbar: Taskbar) {
  private val log = KetchLogger("TaskbarFeedback")
  private var applied: Pair<TaskbarModel, Window?>? = null

  fun apply(model: TaskbarModel, window: Window?) {
    if (applied == model to window) return
    applied = model to window
    attempt(Taskbar.Feature.ICON_BADGE_TEXT) { taskbar.setIconBadge(model.badge) }
    attempt(Taskbar.Feature.PROGRESS_VALUE) { taskbar.setProgressValue(model.progress) }
    if (window == null) return
    attempt(Taskbar.Feature.PROGRESS_VALUE_WINDOW) {
      taskbar.setWindowProgressValue(window, model.windowProgress)
    }
    attempt(Taskbar.Feature.PROGRESS_STATE_WINDOW) {
      taskbar.setWindowProgressState(window, model.state.awt)
    }
    attempt(Taskbar.Feature.ICON_BADGE_IMAGE_WINDOW) {
      taskbar.setWindowIconBadge(window, model.badge?.let(::badgeImage))
    }
  }

  fun requestAttention() {
    attempt(Taskbar.Feature.USER_ATTENTION) { taskbar.requestUserAttention(true, false) }
  }

  private fun attempt(feature: Taskbar.Feature, block: () -> Unit) {
    if (!taskbar.isSupported(feature)) return
    try {
      block()
    } catch (e: Exception) {
      log.d { "Couldn't update $feature: ${e.describeCauses()}" }
    }
  }

  companion object {
    // Linux docks only show what the window manager offers, so Ketch leaves them alone.
    fun create(): TaskbarApplier? {
      val supported = DesktopOs.current != DesktopOs.LINUX
      if (!supported || !Taskbar.isTaskbarSupported()) return null
      return TaskbarApplier(Taskbar.getTaskbar())
    }
  }
}

// The count bitmap Windows overlays on the taskbar button.
private fun badgeImage(text: String): BufferedImage {
  val image = BufferedImage(BADGE_SIZE, BADGE_SIZE, BufferedImage.TYPE_INT_ARGB)
  val graphics = image.createGraphics()
  try {
    graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
    graphics.setRenderingHint(
      RenderingHints.KEY_TEXT_ANTIALIASING,
      RenderingHints.VALUE_TEXT_ANTIALIAS_ON,
    )
    graphics.color = Color(lightKetchColors().dangerFill.toArgb(), true)
    graphics.fillOval(0, 0, BADGE_SIZE, BADGE_SIZE)
    val label = if (text.length > 2) "99+" else text
    val fontSize = if (label.length > 1) BADGE_FONT_SMALL else BADGE_FONT
    graphics.font = Font(Font.SANS_SERIF, Font.BOLD, fontSize)
    // Failure badges are white on the danger fill.
    graphics.color = Color.WHITE
    val metrics = graphics.fontMetrics
    val x = (BADGE_SIZE - metrics.stringWidth(label)) / 2
    val y = (BADGE_SIZE - metrics.height) / 2 + metrics.ascent
    graphics.drawString(label, x, y)
  } finally {
    graphics.dispose()
  }
  return image
}

private const val FULL_PROGRESS = 100
private const val BADGE_SIZE = 16
private const val BADGE_FONT = 11
private const val BADGE_FONT_SMALL = 8
