package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.lazy.LazyListItemInfo
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isMetaPressed
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Where a rubber band started and how far the list has scrolled under it, in the list's own
 * coordinates.
 *
 * @property anchor where the drag started.
 * @property above index of the last item above [anchor], or -1.
 * @property below index of the first item below [anchor], or the item count.
 */
private class Band(val anchor: Offset, val above: Int, val below: Int) {
  var pointer: Offset by mutableStateOf(anchor)
  var scrolled: Float by mutableStateOf(0f)
}

/**
 * The item indices a rubber band covers: from the items next to where it started, [above] and
 * [below] that point, to the item under the pointer at [pointerY] in [items], or the nearest one
 * toward the start. [anchorY] and [pointerY] are positions in the list's coordinates, with
 * [scrolled] pixels scrolled since the band started. Empty when the band covers no item.
 */
internal fun bandRange(
  anchorY: Float,
  above: Int,
  below: Int,
  pointerY: Float,
  scrolled: Float,
  items: List<LazyListItemInfo>,
  viewportStart: Int,
): IntRange {
  fun top(item: LazyListItemInfo) = (item.offset - viewportStart).toFloat()
  val hit = items.firstOrNull { pointerY >= top(it) && pointerY < top(it) + it.size }?.index
  return if (pointerY + scrolled >= anchorY) {
    val end = hit ?: items.lastOrNull { top(it) + it.size <= pointerY }?.index
    if (end == null) IntRange.EMPTY else below..end
  } else {
    val start = hit ?: items.firstOrNull { top(it) >= pointerY }?.index
    if (start == null) IntRange.EMPTY else start..above
  }
}

/**
 * Selects rows by dragging a band from empty space in the list, such as below its last row.
 * Every row the band crosses is selected; with ⌘ (Ctrl elsewhere) held they add to the
 * selection. Near the top or bottom edge the list scrolls on, faster the closer the pointer.
 * A click on empty space clears the selection. Pointer only: a finger scrolls.
 *
 * Put it on the `LazyColumn` of [listState].
 *
 * @param keyAt the task key of the item at an index, or `null` for group headers and the like.
 * @param onStart runs when a band starts, such as to move the keyboard focus to the list.
 */
@Composable
internal fun Modifier.rubberBand(
  listState: LazyListState,
  selection: ListSelection,
  keyAt: (Int) -> TaskKey?,
  onStart: () -> Unit = {},
): Modifier {
  val colors = KetchTheme.colors
  val shape = KetchTheme.spacing.s1
  val edge = KetchTheme.spacing.s12
  val scope = rememberCoroutineScope()
  var band by remember { mutableStateOf<Band?>(null) }
  val currentKeyAt by rememberUpdatedState(keyAt)
  val currentOnStart by rememberUpdatedState(onStart)
  val edgePx = with(LocalDensity.current) { edge.toPx() }

  fun update(active: Band, base: Set<TaskKey>) {
    val info = listState.layoutInfo
    val range = bandRange(
      anchorY = active.anchor.y,
      above = active.above,
      below = active.below,
      pointerY = active.pointer.y,
      scrolled = active.scrolled,
      items = info.visibleItemsInfo,
      viewportStart = info.viewportStartOffset,
    )
    val covered = range.mapNotNull { currentKeyAt(it) }
    selection.update(selection.current.band(covered, base))
  }

  return this
    .pointerInput(listState, selection) {
      val apple = KeyboardPlatform.current.isApple
      awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false)
        if (down.type == PointerType.Touch || !currentEvent.buttons.isPrimaryPressed) {
          return@awaitEachGesture
        }
        val info = listState.layoutInfo
        val start = info.viewportStartOffset
        val y = down.position.y
        val items = info.visibleItemsInfo
        if (items.any { y >= it.offset - start && y < it.offset - start + it.size }) {
          return@awaitEachGesture
        }
        val keys = currentEvent.keyboardModifiers
        val additive = if (apple) keys.isMetaPressed else keys.isCtrlPressed
        val base = if (additive) selection.current.selected else emptySet()
        val above = items.lastOrNull { it.offset - start + it.size <= y }?.index ?: -1
        val below = items.firstOrNull { it.offset - start >= y }?.index ?: info.totalItemsCount
        var active: Band? = null
        var scroller: Job? = null
        try {
          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            if (!change.pressed) {
              // A click on empty space, as in file managers, deselects.
              if (active == null && !additive && !change.isConsumed) selection.clear()
              break
            }
            var running = active
            if (running == null) {
              if ((change.position - down.position).getDistance() < viewConfiguration.touchSlop) {
                continue
              }
              val started = Band(down.position, above, below)
              running = started
              active = started
              band = started
              currentOnStart()
              scroller = scope.launch {
                autoScroll(listState, started, edgePx, size.height.toFloat()) {
                  update(started, base)
                }
              }
            }
            change.consume()
            running.pointer = change.position
            update(running, base)
          }
        } finally {
          scroller?.cancel()
          band = null
        }
      }
    }
    .drawWithContent {
      drawContent()
      val active = band ?: return@drawWithContent
      val anchor = active.anchor.copy(y = active.anchor.y - active.scrolled)
      val rect = Rect(anchor, active.pointer).normalized()
        .intersect(Rect(Offset.Zero, size))
      if (rect.isEmpty) return@drawWithContent
      val corner = CornerRadius(shape.toPx())
      drawRoundRect(colors.accent, rect.topLeft, rect.size, corner, alpha = BAND_FILL_ALPHA)
      drawRoundRect(
        color = colors.accent,
        topLeft = rect.topLeft,
        size = rect.size,
        cornerRadius = corner,
        // One dp.
        style = Stroke(width = density),
      )
    }
}

/**
 * Scrolls [listState] while [band]'s pointer is within [edge] pixels of either end of a list
 * [height] pixels tall, calling [onScrolled] after each step.
 */
private suspend fun autoScroll(
  listState: LazyListState,
  band: Band,
  edge: Float,
  height: Float,
  onScrolled: () -> Unit,
) {
  while (true) {
    withFrameNanos {}
    val y = band.pointer.y
    val depth = when {
      y < edge -> y - edge
      y > height - edge -> y - (height - edge)
      else -> 0f
    }
    if (depth == 0f) continue
    val step = (depth / edge).coerceIn(-1f, 1f) * edge / AUTO_SCROLL_FRAMES
    val moved = listState.scrollBy(step)
    if (moved != 0f) {
      band.scrolled += moved
      onScrolled()
    }
  }
}

private fun Rect.normalized(): Rect =
  Rect(minOf(left, right), minOf(top, bottom), maxOf(left, right), maxOf(top, bottom))

/** Frames the list takes to scroll one edge's height at full speed. */
private const val AUTO_SCROLL_FRAMES = 6f

/** Opacity of the band's accent fill, light enough to read the rows through. */
private const val BAND_FILL_ALPHA = 0.08f
