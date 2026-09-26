package com.linroid.ketch.app.desktop

import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.WindowState
import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Toolkit
import java.io.File
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.milliseconds

// Wide enough for the sidebar layout, which the shared UI switches to at the
// 840dp expanded breakpoint, and tall enough to show the whole sidebar.
internal const val DEFAULT_WINDOW_WIDTH = 1024
internal const val DEFAULT_WINDOW_HEIGHT = 720

private val SAVE_DELAY = 500.milliseconds

/**
 * Floating bounds of the main window in window (AWT) coordinates, plus
 * whether it was maximized. Compose window dp map 1:1 to these coordinates.
 */
internal data class WindowBounds(
  val x: Int,
  val y: Int,
  val width: Int,
  val height: Int,
  val maximized: Boolean,
)

/**
 * Remembers where the user left the main window, since neither the OS nor
 * AWT restores the frame of a JVM window on relaunch.
 */
internal class WindowStateStore(private val file: File) {
  private val log = KetchLogger("WindowState")

  fun load(): WindowBounds? {
    if (!file.isFile) return null
    val props = Properties()
    try {
      file.inputStream().use { props.load(it) }
    } catch (e: IOException) {
      log.w(e) { "Failed to read ${file.path}" }
      return null
    } catch (e: IllegalArgumentException) {
      log.w(e) { "Malformed ${file.path}" }
      return null
    }
    fun int(key: String) = props.getProperty(key)?.toIntOrNull()
    return WindowBounds(
      x = int("x") ?: return null,
      y = int("y") ?: return null,
      width = int("width")?.takeIf { it > 0 } ?: return null,
      height = int("height")?.takeIf { it > 0 } ?: return null,
      maximized = props.getProperty("maximized").toBoolean(),
    )
  }

  fun save(bounds: WindowBounds) {
    val props = Properties().apply {
      setProperty("x", bounds.x.toString())
      setProperty("y", bounds.y.toString())
      setProperty("width", bounds.width.toString())
      setProperty("height", bounds.height.toString())
      setProperty("maximized", bounds.maximized.toString())
    }
    try {
      file.parentFile?.mkdirs()
      val tmp = File(file.path + ".tmp")
      tmp.outputStream().use { props.store(it, null) }
      Files.move(
        tmp.toPath(), file.toPath(),
        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE,
      )
    } catch (e: IOException) {
      log.w(e) { "Failed to save ${file.path}" }
    }
  }

  /**
   * Saves [state] shortly after each move, resize or maximize, until
   * cancelled. Saving as it changes, rather than on close, also covers
   * quitting with Cmd+Q, which exits without closing the window.
   */
  suspend fun saveChanges(state: WindowState, initial: WindowBounds?) {
    // Maximized and fullscreen windows report the screen's bounds, so keep
    // the last floating bounds to restore when the window is unmaximized.
    var floating = initial
    snapshotFlow {
      WindowSnapshot(state.placement, state.position, state.size, state.isMinimized)
    }.collectLatest { snapshot ->
      if (snapshot.minimized) return@collectLatest
      val position = snapshot.position
      if (snapshot.placement == WindowPlacement.Floating &&
        position is WindowPosition.Absolute
      ) {
        floating = WindowBounds(
          x = position.x.value.roundToInt(),
          y = position.y.value.roundToInt(),
          width = snapshot.size.width.value.roundToInt(),
          height = snapshot.size.height.value.roundToInt(),
          maximized = false,
        )
      }
      val bounds = floating?.copy(
        maximized = snapshot.placement == WindowPlacement.Maximized,
      ) ?: return@collectLatest
      delay(SAVE_DELAY)
      withContext(Dispatchers.IO) { save(bounds) }
    }
  }

  private data class WindowSnapshot(
    val placement: WindowPlacement,
    val position: WindowPosition,
    val size: DpSize,
    val minimized: Boolean,
  )
}

/**
 * Builds the initial window state from [saved] bounds, keeping the window
 * fully on the screen it mostly overlapped. Centers it on the primary screen
 * when that screen is no longer connected, or at the default size when
 * nothing was saved.
 *
 * @param screens usable bounds of each connected screen, primary first
 */
internal fun initialWindowState(
  saved: WindowBounds?,
  screens: List<Rectangle> = usableScreenBounds(),
): WindowState {
  val placement = if (saved?.maximized == true) {
    WindowPlacement.Maximized
  } else {
    WindowPlacement.Floating
  }
  val screen = saved?.let { screens.mostOverlapping(it) }
  if (saved == null || screen == null) {
    val primary = screens.first()
    return WindowState(
      placement = placement,
      position = WindowPosition(Alignment.Center),
      size = DpSize(
        width = min(saved?.width ?: DEFAULT_WINDOW_WIDTH, primary.width).dp,
        height = min(saved?.height ?: DEFAULT_WINDOW_HEIGHT, primary.height).dp,
      ),
    )
  }
  val width = min(saved.width, screen.width)
  val height = min(saved.height, screen.height)
  return WindowState(
    placement = placement,
    position = WindowPosition(
      x = saved.x.coerceIn(screen.x, screen.x + screen.width - width).dp,
      y = saved.y.coerceIn(screen.y, screen.y + screen.height - height).dp,
    ),
    size = DpSize(width.dp, height.dp),
  )
}

private fun List<Rectangle>.mostOverlapping(bounds: WindowBounds): Rectangle? {
  val window = Rectangle(bounds.x, bounds.y, bounds.width, bounds.height)
  return map { it to it.intersection(window) }
    .filter { (_, overlap) -> !overlap.isEmpty }
    .maxByOrNull { (_, overlap) -> overlap.width.toLong() * overlap.height }
    ?.first
}

// Screen bounds minus the menu bar, dock and taskbar, primary screen first.
private fun usableScreenBounds(): List<Rectangle> {
  val env = GraphicsEnvironment.getLocalGraphicsEnvironment()
  val toolkit = Toolkit.getDefaultToolkit()
  val primary = env.defaultScreenDevice
  return (listOf(primary) + env.screenDevices.filter { it != primary })
    .map { device ->
      val config = device.defaultConfiguration
      val bounds = config.bounds
      val insets = toolkit.getScreenInsets(config)
      Rectangle(
        bounds.x + insets.left,
        bounds.y + insets.top,
        bounds.width - insets.left - insets.right,
        bounds.height - insets.top - insets.bottom,
      )
    }
}
