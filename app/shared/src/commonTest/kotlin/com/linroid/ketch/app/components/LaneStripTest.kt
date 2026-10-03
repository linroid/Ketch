package com.linroid.ketch.app.components

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.i18n.load
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LaneStripTest {
  @Test
  fun laneLayout_unevenSegments_placesEachAtItsByteOffsets() {
    val layout = laneLayout(
      segments = listOf(
        Segment(0, 0, 99, downloadedBytes = 100),
        Segment(1, 100, 399, downloadedBytes = 150),
        Segment(2, 400, 999, downloadedBytes = 0)
      ),
      progress = null,
    )

    assertEquals(3, layout.count)
    assertFloats(listOf(0f, 0.1f, 0.4f), layout.starts)
    assertFloats(listOf(0.1f, 0.4f, 1f), layout.ends)
    assertFloats(listOf(0.1f, 0.25f, 0.4f), layout.fills)
    assertEquals(0.25f, layout.fraction!!, TOLERANCE)
  }

  @Test
  fun laneLayout_unsortedSegments_ordersThemByStart() {
    val layout = laneLayout(
      segments = listOf(Segment(1, 500, 999), Segment(0, 0, 499)),
      progress = null,
    )

    assertEquals(listOf(0L, 500L), layout.keys.toList())
  }

  @Test
  fun laneLayout_thirtyTwoUnfinishedSegments_drawsEveryLaneWithSeamsBetween() {
    val layout = laneLayout(evenSegments(32, downloaded = 10), progress = null)

    assertEquals(32, layout.count)
    assertEquals(32, layout.unfinished.count { it })
    assertEquals(31, layout.seams.count { it })
    assertFalse(layout.seams[0])
  }

  @Test
  fun laneLayout_moreThanThirtyTwoSegments_drawsNoSeams() {
    val layout = laneLayout(evenSegments(33, downloaded = 10), progress = null)

    assertEquals(33, layout.count)
    assertEquals(0, layout.seams.count { it })
  }

  @Test
  fun laneLayout_finishedNeighbours_shareTheFirstColorWithoutSeams() {
    val layout = laneLayout(
      segments = listOf(
        Segment(0, 0, 99, downloadedBytes = 100),
        Segment(1, 100, 199, downloadedBytes = 100),
        Segment(2, 200, 299, downloadedBytes = 40),
        Segment(3, 300, 399, downloadedBytes = 10)
      ),
      progress = null,
    )

    assertEquals(listOf(0, 0, 2, 3), layout.colors.toList())
    assertEquals(listOf(false, false, false, true), layout.seams.toList())
  }

  @Test
  fun laneLayout_noSegmentsWithKnownSize_drawsOneLane() {
    val layout = laneLayout(emptyList(), DownloadProgress(downloadedBytes = 300, totalBytes = 1200))

    assertEquals(1, layout.count)
    assertFloats(listOf(0.25f), layout.fills)
    assertTrue(layout.unfinished[0])
  }

  @Test
  fun laneLayout_noSegmentsAndUnknownSize_hasNothingToDraw() {
    val layout = laneLayout(emptyList(), DownloadProgress(downloadedBytes = 300, totalBytes = -1))

    assertEquals(0, layout.count)
    assertNull(layout.fraction)
  }

  @Test
  fun laneLayout_onlyEmptyFiles_hasNothingToDraw() {
    val layout = laneLayout(listOf(Segment(0, 0, -1)), DownloadProgress(0, totalBytes = 0))

    assertEquals(0, layout.count)
    assertNull(layout.fraction)
  }

  @Test
  fun laneLayout_nextSnapshot_glidesFromWhereTheFillIsDrawn() {
    val first = laneLayout(listOf(Segment(0, 0, 999, downloadedBytes = 100)), progress = null)
    val second = laneLayout(
      segments = listOf(Segment(0, 0, 999, downloadedBytes = 200)),
      progress = null,
      previous = first,
      glide = 1f,
    )
    val third = laneLayout(
      segments = listOf(Segment(0, 0, 999, downloadedBytes = 300)),
      progress = null,
      previous = second,
      glide = 0.5f,
    )

    assertFloats(listOf(0.1f), second.fillsFrom)
    assertFloats(listOf(0.15f), third.fillsFrom)
    assertFloats(listOf(0.3f), third.fills)
  }

  @Test
  fun laneLayout_firstSnapshot_marksNoSeamAsNew() {
    val layout = laneLayout(evenSegments(4, downloaded = 10), progress = null)

    assertFalse(layout.resegmented)
    assertFloats(layout.starts.toList(), layout.startsFrom)
  }

  @Test
  fun laneLayout_progressOnly_keepsTheSeamsAndFlashesNothing() {
    val first = laneLayout(evenSegments(4, downloaded = 10), progress = null)
    val second = laneLayout(evenSegments(4, downloaded = 20), progress = null, previous = first)

    assertFalse(second.resegmented)
  }

  @Test
  fun laneLayout_resplit_growsNewSeamsFromTheOldWriteHead() {
    val before = laneLayout(listOf(Segment(0, 0, 999, downloadedBytes = 400)), progress = null)
    val after = laneLayout(
      segments = listOf(
        Segment(0, 0, 399, downloadedBytes = 400),
        Segment(1, 400, 599),
        Segment(2, 600, 799),
        Segment(3, 800, 999)
      ),
      progress = null,
      previous = before,
    )

    assertTrue(after.resegmented)
    assertEquals(listOf(false, false, true, true), after.seams.toList())
    assertEquals(listOf(false, false, true, true), after.newSeams.toList())
    assertFloats(listOf(0f, 0.4f, 0.4f, 0.4f), after.startsFrom)
  }

  @Test
  fun laneLayout_progressTickDuringResplit_keepsSpringingAndFlashing() {
    val after = resplit()
    val next = laneLayout(
      segments = listOf(
        Segment(0, 0, 399, downloadedBytes = 400),
        Segment(1, 400, 599, downloadedBytes = 10),
        Segment(2, 600, 799, downloadedBytes = 10),
        Segment(3, 800, 999, downloadedBytes = 10)
      ),
      progress = null,
      previous = after,
      reseg = 0.5f,
    )

    assertFalse(next.resegmented)
    assertFloats(listOf(0f, 0.4f, 0.4f, 0.4f), next.startsFrom)
    assertEquals(listOf(false, false, true, true), next.newSeams.toList())
  }

  @Test
  fun laneLayout_resplitWhileSpringing_startsFromWhereTheSeamsAreDrawn() {
    val after = resplit()
    val again = laneLayout(
      segments = listOf(
        Segment(0, 0, 399, downloadedBytes = 400),
        Segment(1, 400, 499),
        Segment(2, 500, 599),
        Segment(3, 600, 799),
        Segment(4, 800, 999)
      ),
      progress = null,
      previous = after,
      reseg = 0.5f,
    )

    assertTrue(again.resegmented)
    assertFloats(listOf(0f, 0.4f, 0.4f, 0.5f, 0.6f), again.startsFrom)
    assertEquals(listOf(false, false, true, false, false), again.newSeams.toList())
  }

  @Test
  fun laneLayout_firstSegmentsAfterProgressOnly_springsNothing() {
    val first = laneLayout(emptyList(), DownloadProgress(downloadedBytes = 0, totalBytes = 1000))
    val second = laneLayout(evenSegments(4, downloaded = 0, length = 250), null, previous = first)

    assertFalse(second.resegmented)
    assertFloats(second.starts.toList(), second.startsFrom)
  }

  @Test
  fun laneLayout_previousFileOfAnotherSize_startsOverWithoutMotion() {
    val first = laneLayout(listOf(Segment(0, 0, 999, downloadedBytes = 400)), progress = null)
    val second = laneLayout(evenSegments(4, downloaded = 100, length = 500), null, previous = first)

    assertFalse(second.resegmented)
    assertFloats(second.fills.toList(), second.fillsFrom)
    assertFloats(second.starts.toList(), second.startsFrom)
  }

  @Test
  fun laneLayout_stalledStart_marksThatLane() {
    val segments = evenSegments(3, downloaded = 10)
    val layout = laneLayout(segments, progress = null, stalled = setOf(segments[1].start))

    assertEquals(listOf(false, true, false), layout.stalled.toList())
  }

  @Test
  fun laneStripDescription_downloading_countsConnectionsActiveAndPercent() = runTest {
    val segments = evenSegments(8, downloaded = 50).mapIndexed { i, segment ->
      if (i < 2) segment.copy(downloadedBytes = segment.totalBytes) else segment
    }

    assertEquals(
      "8 connections, 6 active, 62 percent",
      laneStripDescription(segments, progress = null, phase = LanePhase.Downloading).load()
    )
    assertEquals(
      "8 connections, 6 active, 62 percent, 1 stalled",
      laneStripDescription(segments, null, LanePhase.Downloading, stalled = 1).load()
    )
  }

  @Test
  fun laneStripDescription_paused_leavesOutActiveConnections() = runTest {
    val segments = listOf(Segment(0, 0, 99, downloadedBytes = 42))

    assertEquals(
      "1 connection, 42 percent",
      laneStripDescription(segments, progress = null, phase = LanePhase.Paused).load()
    )
  }

  @Test
  fun laneStripDescription_unknownSize_saysSo() = runTest {
    val progress = DownloadProgress(downloadedBytes = 300, totalBytes = -1)

    assertEquals(
      "size unknown",
      laneStripDescription(emptyList(), progress, phase = LanePhase.Downloading).load()
    )
  }

  @Test
  fun laneFraction_completed_isWhole() {
    assertEquals(1f, laneFraction(emptyList(), progress = null, phase = LanePhase.Completed))
  }

  @Test
  fun completionFade_reducedMotion_fadesAtOnceWithoutASheen() {
    assertEquals(1f, completionFade(completion = 0f, fadeMillis = 0, sheenMillis = 0))
    val sheen = completionSheen(completion = 0f, fadeMillis = 0, sheenMillis = 0)
    assertFalse(sheen > 0f && sheen < 1f)
  }

  @Test
  fun completionSheen_afterTheFade_crossesTheStripOnce() {
    val fade = 220
    val sheen = 320
    val total = (fade + sheen).toFloat()

    assertEquals(0.5f, completionFade(110 / total, fade, sheen), TOLERANCE)
    assertTrue(completionSheen(110 / total, fade, sheen) < 0f)
    assertEquals(1f, completionFade(220 / total, fade, sheen), TOLERANCE)
    assertEquals(0.5f, completionSheen(380 / total, fade, sheen), TOLERANCE)
    assertEquals(1f, completionSheen(1f, fade, sheen), TOLERANCE)
  }

  /** One connection 40% done, re-split into four: three new lanes after the done part. */
  private fun resplit(): LaneLayout {
    val before = laneLayout(listOf(Segment(0, 0, 999, downloadedBytes = 400)), progress = null)
    return laneLayout(
      segments = listOf(
        Segment(0, 0, 399, downloadedBytes = 400),
        Segment(1, 400, 599),
        Segment(2, 600, 799),
        Segment(3, 800, 999)
      ),
      progress = null,
      previous = before,
    )
  }

  private fun evenSegments(count: Int, downloaded: Long, length: Long = 100): List<Segment> =
    List(count) { Segment(it, it * length, it * length + length - 1, downloaded) }

  private fun assertFloats(expected: List<Float>, actual: FloatArray) {
    assertEquals(expected.size, actual.size, "sizes differ: $expected vs ${actual.toList()}")
    expected.forEachIndexed { i, value -> assertEquals(value, actual[i], TOLERANCE, "at $i") }
  }

  private companion object {
    const val TOLERANCE = 0.0001f
  }
}
