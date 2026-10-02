package com.linroid.ketch.app.ui.downloads

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.layout.layout
import kotlin.math.roundToInt

/**
 * The phone's status chips as they scroll away: scrolling the list down slides them up after the
 * shell's top bar, and scrolling up brings them back at once. Put [connection] on a parent of the
 * list and the chips in [Bar].
 */
@Stable
internal class CollapsingChips {
  private var offset by mutableFloatStateOf(0f)
  private var height by mutableFloatStateOf(0f)

  /** Receives the list's scrolling once the top bar has taken its share. */
  val connection: NestedScrollConnection = object : NestedScrollConnection {
    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
      val target = (offset + available.y).coerceIn(-height, 0f)
      val consumed = target - offset
      offset = target
      return Offset(0f, consumed)
    }
  }

  /** Lays [content] out moved up by how far the chips have scrolled away, and clipped. */
  @Composable
  fun Bar(content: @Composable () -> Unit) {
    Box(
      Modifier
        .clipToBounds()
        .layout { measurable, constraints ->
          val placeable = measurable.measure(constraints)
          height = placeable.height.toFloat()
          val shift = offset.roundToInt().coerceIn(-placeable.height, 0)
          layout(placeable.width, placeable.height + shift) { placeable.place(0, shift) }
        }
    ) {
      content()
    }
  }
}
