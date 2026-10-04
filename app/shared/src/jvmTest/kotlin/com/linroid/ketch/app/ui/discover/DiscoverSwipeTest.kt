package com.linroid.ketch.app.ui.discover

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import com.linroid.ketch.app.snapshot.nodes
import com.linroid.ketch.app.snapshot.onUiThread
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.config.DensityMode
import kotlinx.coroutines.CoroutineDispatcher
import kotlin.coroutines.CoroutineContext
import kotlin.test.Test
import kotlin.test.assertEquals

/** Swiping a result away on a phone, as the thread drops the row and Undo brings it back. */
class DiscoverSwipeTest {
  @OptIn(ExperimentalComposeUiApi::class)
  @Test
  fun swipedAwayRow_restoredByUndo_staysWithoutBeingDiscardedAgain() = onUiThread {
    val dispatcher = QueueDispatcher()
    val shown = mutableStateListOf(*Rows.toTypedArray())
    val discarded = mutableListOf<String>()
    val scene = ImageComposeScene(
      width = WIDTH,
      height = HEIGHT,
      density = Density(1f),
      coroutineContext = dispatcher,
    ) {
      KetchTheme(darkTheme = false, density = DensityMode.Comfortable, reduceMotion = false) {
        // As the thread keeps its rows: by key, so Undo brings back the same item.
        LazyColumn(Modifier.fillMaxSize()) {
          items(shown, key = { it.url }) { candidate ->
            ResultRow(
              candidate = candidate,
              selected = false,
              onToggle = {},
              onDiscard = {
                discarded += candidate.url
                shown.remove(candidate)
              },
              padding = KetchTheme.spacing.s2,
              narrow = true,
              modifier = Modifier.animateItem(),
            )
          }
        }
      }
    }
    try {
      var time = 0L
      fun frame() {
        scene.render(time * NANOS_PER_MILLI)
        dispatcher.drain()
        time += FRAME_MILLIS
      }
      repeat(SETTLE_FRAMES) { frame() }
      val row = scene.rowCenterY(Rows[1])

      // A finger drags the second row toward the start, past the threshold.
      scene.sendPointerEvent(
        PointerEventType.Press,
        Offset(WIDTH - EDGE, row),
        timeMillis = time,
        type = PointerType.Touch,
      )
      var x = WIDTH - EDGE
      while (x > EDGE) {
        frame()
        x -= STEP
        scene.sendPointerEvent(
          PointerEventType.Move,
          Offset(x, row),
          timeMillis = time,
          type = PointerType.Touch,
        )
        dispatcher.drain()
      }
      scene.sendPointerEvent(
        PointerEventType.Release,
        Offset(x, row),
        timeMillis = time,
        type = PointerType.Touch,
      )
      repeat(SETTLE_FRAMES) { frame() }
      assertEquals(listOf(Rows[1].url), discarded)

      // Undo puts the row back where it was.
      shown.add(1, Rows[1])
      repeat(SETTLE_FRAMES) { frame() }

      assertEquals(Rows, shown.toList(), "The row stays")
      assertEquals(listOf(Rows[1].url), discarded, "The row is discarded only once")
    } finally {
      scene.close()
    }
  }

  /** The middle of [candidate]'s row, found by its title. */
  private fun ImageComposeScene.rowCenterY(candidate: AiCandidate): Float {
    val title = nodes().first { node ->
      node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == candidate.title } == true
    }
    return title.boundsInRoot.center.y
  }

  /** Runs what the scene dispatches only when asked, so frames and their work interleave. */
  private class QueueDispatcher : CoroutineDispatcher() {
    private val queue = ArrayDeque<Runnable>()

    override fun dispatch(context: CoroutineContext, block: Runnable) {
      queue.addLast(block)
    }

    fun drain() {
      while (queue.isNotEmpty()) queue.removeFirst().run()
    }
  }

  private companion object {
    const val WIDTH = 400
    const val HEIGHT = 700
    const val EDGE = 20f
    const val STEP = 40f
    const val FRAME_MILLIS = 16L
    const val NANOS_PER_MILLI = 1_000_000L
    const val SETTLE_FRAMES = 120

    val Rows = listOf("blender.dmg", "ubuntu.iso", "ffmpeg.tar.xz").map { name ->
      AiCandidate(
        url = "https://example.com/$name",
        title = name,
        sourceUrl = "https://example.com/downloads",
        confidence = 0.9f,
        description = "",
      )
    }
  }
}
