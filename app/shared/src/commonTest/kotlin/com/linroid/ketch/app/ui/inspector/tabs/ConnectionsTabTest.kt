package com.linroid.ketch.app.ui.inspector.tabs

import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.ListFixtures.downloading
import com.linroid.ketch.app.state.ListFixtures.row
import com.linroid.ketch.app.state.SpeedHistoryStore
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.lightKetchColors
import com.linroid.ketch.app.util.LaneHealth
import com.linroid.ketch.app.util.SegmentRate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionsTabTest {
  private val colors = lightKetchColors()

  @Test
  fun store_laneWithoutBytesForThreeSeconds_turnsAmber() = runTest {
    val rows = MutableStateFlow(listOf(twoLanes(moving = 0, idle = 100)))
    val store = SpeedHistoryStore(
      rows,
      backgroundScope,
      ListFixtures.clock(this),
      testScheduler.timeSource
    )
    val key = TaskKey(LOCAL_DEVICE_ID, "a")
    runCurrent()
    val healths = mutableListOf<LaneHealth?>()
    val expected = listOf(LaneHealth.Moving, LaneHealth.Moving, LaneHealth.Stalled)

    for (second in 1..3) {
      rows.value = listOf(twoLanes(moving = second * 100L, idle = 100))
      advanceTimeBy(1_000)
      runCurrent()
      val model = connectionsModel(rows.value.single().segments, store.rates(key), true)
      healths += model.lanes.single { it.segment.start == 1000L }.rate?.health
    }

    assertEquals<List<LaneHealth?>>(expected, healths)
    val model = connectionsModel(rows.value.single().segments, store.rates(key), true)
    val idle = model.lanes.single { it.segment.start == 1000L }
    assertEquals(colors.status.paused.color, colors.laneHealthColor(idle.rate!!.health))
    assertEquals(setOf(1000L), model.stalled)
    assertEquals(LaneHealth.Moving, model.lanes.single { it.segment.start == 0L }.rate?.health)
  }

  @Test
  fun laneHealthColor_eachHealth_greenAmberRed() {
    assertEquals(colors.status.completed.color, colors.laneHealthColor(LaneHealth.Moving))
    assertEquals(colors.status.paused.color, colors.laneHealthColor(LaneHealth.Stalled))
    assertEquals(colors.status.failed.color, colors.laneHealthColor(LaneHealth.Stuck))
  }

  @Test
  fun connectionsModel_mixedSegments_lanesForUnfinishedInByteOrder() {
    val segments = listOf(
      seg(2, 2000, 2999, 10),
      seg(0, 0, 999, 1000),
      seg(1, 1000, 1999, 500)
    )
    val rates = listOf(SegmentRate(1000, 300), SegmentRate(2000, 0, stalledFor = 12.seconds))

    val model = connectionsModel(segments, rates, downloading = true)

    assertEquals(listOf(1000L, 2000L), model.lanes.map { it.segment.start })
    assertEquals(listOf(2, 3), model.lanes.map { it.number })
    assertEquals(1, model.finished)
    assertEquals(1000L, model.finishedBytes)
    assertEquals(LaneHealth.Stuck, model.lanes[1].rate?.health)
    assertEquals(setOf(2000L), model.stalled)
  }

  @Test
  fun connectionsModel_notDownloading_hasNoRates() {
    val model = connectionsModel(
      segments = listOf(seg(0, 0, 999, 620)),
      rates = listOf(SegmentRate(0, 300)),
      downloading = false,
    )

    assertNull(model.lanes.single().rate)
    assertEquals(62, model.lanes.single().percent)
    assertTrue(model.stalled.isEmpty())
  }

  @Test
  fun connectionsModel_laneWithoutMeasurement_readsZero() {
    val model = connectionsModel(listOf(seg(0, 0, 999, 0)), emptyList(), downloading = true)

    assertEquals(SegmentRate(0, 0), model.lanes.single().rate)
  }

  @Test
  fun laneScale_byLaneCount_shrinksRows() {
    assertEquals(LaneScale.Tall, LaneScale.of(1))
    assertEquals(LaneScale.Tall, LaneScale.of(8))
    assertEquals(LaneScale.Medium, LaneScale.of(9))
    assertEquals(LaneScale.Medium, LaneScale.of(16))
    assertEquals(LaneScale.Dense, LaneScale.of(17))
    assertEquals(LaneScale.Dense, LaneScale.of(32))
  }

  @Test
  fun connectionsModel_denseLanes_sortSlowestFirstAndAnnotateThree() {
    val segments = (0 until 20).map { seg(it, it * 100L, it * 100L + 99, 10) }
    val rates = segments.map { SegmentRate(it.start, 1000L - it.index * 10) }

    val model = connectionsModel(segments, rates, downloading = true)

    assertEquals(LaneScale.Dense, model.scale)
    assertEquals(listOf(20, 19, 18, 17), model.lanes.take(4).map { it.number })
    assertEquals(listOf(true, true, true, false), model.lanes.take(4).map { it.annotated })
    assertEquals(3, model.lanes.count { it.annotated })
  }

  @Test
  fun connectionsSummary_eachState_countsLanesWithSpeedOrProgress() {
    val model = connectionsModel(
      segments = listOf(seg(0, 0, 999, 1000), seg(1, 1000, 1999, 10), seg(2, 2000, 2999, 10)),
      rates = emptyList(),
      downloading = true,
    )

    assertEquals("2 active · 6.4 MB/s", connectionsSummary(model, true, 6_710_886))
    assertEquals("2 unfinished · 34%", connectionsSummary(model, false, null))
  }

  @Test
  fun connectionsCaption_eachCase_explainsTheLanes() {
    assertEquals("Single connection", connectionsCaption(1, false, true, true))
    assertEquals(
      "This server allows only 1 connection",
      connectionsCaption(1, serverLimited = true, editable = true, downloading = true)
    )
    assertEquals(
      "Changing connections re-splits the remaining bytes live.",
      connectionsCaption(4, serverLimited = false, editable = true, downloading = true)
    )
    assertEquals(
      "A new number of connections applies when the download resumes.",
      connectionsCaption(4, serverLimited = false, editable = true, downloading = false)
    )
    assertNull(connectionsCaption(4, serverLimited = false, editable = false, downloading = false))
  }

  /** Row a downloading over two connections; the first moves, the second does not. */
  private fun twoLanes(moving: Long, idle: Long): TaskRow =
    row("a", downloading(moving + idle, total = 2000))
      .copy(segments = listOf(seg(0, 0, 999, moving), seg(1, 1000, 1999, idle)))

  private fun seg(index: Int, start: Long, end: Long, downloaded: Long) =
    Segment(index = index, start = start, end = end, downloadedBytes = downloaded)
}
