package com.linroid.ketch.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerButtons
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.IntOffset
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.theme.KetchTheme
import org.jetbrains.skia.Image

/**
 * The tray menu of [entries] in a [width] by [height] pixel scene at [scale] pixels per dp, as
 * [TrayMenuWindow] shows it, opened at [pointer] in pixels. Records what its items do.
 */
internal class TrayMenuScene(
  width: Int,
  height: Int,
  scale: Float,
  pointer: IntOffset,
  entries: List<MenuEntry>,
  darkTheme: Boolean = false,
  zoom: Float = 1f,
  backdrop: @Composable (content: @Composable () -> Unit) -> Unit = { it() },
) : AutoCloseable {
  /** What the items chosen did, in order. */
  val actions = mutableListOf<MenuAction>()

  /** How many times the menu asked to close. */
  var dismissals = 0
    private set

  private val scene = ImageComposeScene(width, height, Density(scale)) {
    KetchTheme(darkTheme = darkTheme, reduceMotion = true, zoom = zoom) {
      backdrop {
        TrayMenuContent(pointer, onDismiss = { dismissals++ }) {
          menuEntries(entries, KeyboardPlatform.Pc, actions::add)
        }
      }
    }
  }

  /** Renders frames until nothing changes for a few of them, as strings load. */
  fun settle(): TrayMenuScene {
    var quiet = 0
    var frames = 0
    while (quiet < QUIET_FRAMES && frames < MAX_FRAMES) {
      scene.render(System.nanoTime())
      Thread.sleep(FRAME_MILLIS)
      quiet = if (scene.hasInvalidations()) 0 else quiet + 1
      frames++
    }
    return this
  }

  /** Presses and releases [key] [times] times. */
  @OptIn(InternalComposeUiApi::class)
  fun press(key: Key, times: Int = 1): TrayMenuScene {
    repeat(times) {
      for (type in listOf(KeyEventType.KeyDown, KeyEventType.KeyUp)) {
        scene.sendKeyEvent(KeyEvent(key = key, type = type))
      }
      settle()
    }
    return this
  }

  /** Clicks at [position], in pixels. */
  fun click(position: Offset): TrayMenuScene {
    scene.sendPointerEvent(PointerEventType.Move, position)
    scene.sendPointerEvent(
      eventType = PointerEventType.Press,
      position = position,
      buttons = PointerButtons(isPrimaryPressed = true),
      button = PointerButton.Primary,
    )
    scene.sendPointerEvent(
      eventType = PointerEventType.Release,
      position = position,
      buttons = PointerButtons(),
      button = PointerButton.Primary,
    )
    return settle()
  }

  /** Bounds of the text [label] in the scene, in pixels, or `null` when it does not show. */
  @OptIn(ExperimentalComposeUiApi::class)
  fun boundsOf(label: String): Rect? = scene.semanticsOwners
    .flatMap { it.unmergedRootSemanticsNode.withDescendants() }
    .firstOrNull { node ->
      node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
    }
    ?.boundsInWindow

  /** The current frame. */
  fun image(): Image = scene.render(System.nanoTime())

  override fun close() = scene.close()

  private fun SemanticsNode.withDescendants(): List<SemanticsNode> =
    listOf(this) + children.flatMap { it.withDescendants() }

  private companion object {
    const val QUIET_FRAMES = 4
    const val MAX_FRAMES = 120
    const val FRAME_MILLIS = 16L
  }
}
