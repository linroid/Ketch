package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.state.ListFixtures.START
import com.linroid.ketch.app.state.ListFixtures.downloading
import com.linroid.ketch.app.state.ListFixtures.row
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedHistoryStoreTest {
  private val a = TaskKey(LOCAL_DEVICE_ID, "a")
  private val b = TaskKey(LOCAL_DEVICE_ID, "b")
  private val paused = DownloadState.Paused(DownloadProgress(10, 1000))

  @Test
  fun record_eachSecond_appendsSamples() = runTest {
    val store = store()

    store.record(START, mapOf(a to 100L))
    store.record(START + 1.seconds, mapOf(a to 200L))

    val history = store.history(a)!!
    assertEquals(listOf(100L, 200L), history.toList())
    assertEquals(START + 1.seconds, history.lastAt)
    assertEquals(START, history.timeAt(0))
  }

  @Test
  fun record_missedSeconds_fillsThemWithZero() = runTest {
    val store = store()

    store.record(START, mapOf(a to 100L))
    store.record(START + 4.seconds, mapOf(a to 400L))

    val history = store.history(a)!!
    assertEquals(listOf(100L, 0L, 0L, 0L, 400L), history.toList())
    assertEquals(START, history.timeAt(0))
  }

  @Test
  fun record_withinHalfASecond_replacesTheLastSample() = runTest {
    val store = store()

    store.record(START, mapOf(a to 100L))
    store.record(START + 300.milliseconds, mapOf(a to 300L))

    assertEquals(listOf(300L), store.history(a)!!.toList())
    assertEquals(START, store.history(a)!!.lastAt)
  }

  @Test
  fun record_pastCapacity_keepsTheNewestSamples() = runTest {
    val store = store()

    repeat(SpeedHistoryStore.CAPACITY + 5) { second ->
      store.record(START + second.seconds, mapOf(a to second.toLong()))
    }

    val history = store.history(a)!!
    assertEquals(SpeedHistoryStore.CAPACITY, history.size)
    assertEquals(5L, history[0])
    assertEquals(304L, history[history.size - 1])
  }

  @Test
  fun record_longGap_keepsCapacity() = runTest {
    val store = store()

    store.record(START, mapOf(a to 100L))
    store.record(START + 1000.seconds, mapOf(a to 200L))

    val history = store.history(a)!!
    assertEquals(SpeedHistoryStore.CAPACITY, history.size)
    assertEquals(0L, history[0])
    assertEquals(200L, history[history.size - 1])
  }

  @Test
  fun record_pausedTaskKeptAndMissingTaskForgotten() = runTest {
    val store = store()
    store.record(START, mapOf(a to 100L, b to 50L))

    store.record(START + 1.seconds, mapOf(a to null))

    assertEquals(listOf(100L), store.history(a)!!.toList())
    assertNull(store.history(b))
  }

  @Test
  fun peakAndAverage_overAllSamples() = runTest {
    val store = store()
    store.record(START, mapOf(a to 100L))
    store.record(START + 2.seconds, mapOf(a to 500L))

    val history = store.history(a)!!

    assertEquals(500L, history.peak)
    assertEquals(200L, history.average)
  }

  @Test
  fun store_downloadingRow_samplesOnceASecondUntilRemoved() = runTest {
    val rows = MutableStateFlow(listOf(row("a", downloading(10, speed = 700))))
    val store = SpeedHistoryStore(rows, backgroundScope, ListFixtures.clock(this))

    advanceTimeBy(3_500)
    val sampled = store.history(a)?.toList()
    rows.value = listOf(row("a", paused))
    advanceTimeBy(5_000)
    val afterPause = store.history(a)?.size
    rows.value = emptyList()
    runCurrent()

    assertEquals(listOf(700L, 700L, 700L, 700L), sampled)
    assertEquals(4, afterPause)
    assertNull(store.history(a))
  }

  @Test
  fun record_withSegments_splitsSpeedByBytesReceived() = runTest {
    val store = store()

    store.record(START, mapOf(a to 100L), mapOf(a to listOf(seg(0, 999, 0), seg(1000, 1999, 0))))
    store.record(
      START + 1.seconds,
      mapOf(a to 300L),
      mapOf(a to listOf(seg(0, 999, 200), seg(1000, 1999, 100)))
    )

    val lanes = store.history(a)!!.lanes
    assertEquals(listOf(0L, 1000L), lanes.map { it.start })
    assertEquals(listOf(50L, 200L), lanes[0].toList())
    assertEquals(listOf(50L, 100L), lanes[1].toList())
  }

  @Test
  fun record_noBytesMoved_keepsTheLastShares() = runTest {
    val store = store()
    store.record(START, mapOf(a to 0L), mapOf(a to listOf(seg(0, 999, 0), seg(1000, 1999, 0))))
    store.record(
      START + 1.seconds,
      mapOf(a to 400L),
      mapOf(a to listOf(seg(0, 999, 300), seg(1000, 1999, 100)))
    )

    store.record(
      START + 2.seconds,
      mapOf(a to 200L),
      mapOf(a to listOf(seg(0, 999, 300), seg(1000, 1999, 100)))
    )

    val lanes = store.history(a)!!.lanes
    assertEquals(150L, lanes[0][2])
    assertEquals(50L, lanes[1][2])
  }

  @Test
  fun record_unevenSplit_partsAddUpToTheSpeed() = runTest {
    val store = store()
    val segments = listOf(seg(0, 999, 0), seg(1000, 1999, 0), seg(2000, 2999, 0))

    store.record(START, mapOf(a to 100L), mapOf(a to segments))

    assertEquals(100L, store.history(a)!!.lanes.sumOf { it[0] })
  }

  @Test
  fun record_segmentFinishedBefore_getsNoLane() = runTest {
    val store = store()
    val segments = listOf(seg(0, 999, 1000), seg(1000, 1999, 0), seg(2000, 2999, 0))

    store.record(START, mapOf(a to 100L), mapOf(a to segments))

    val lanes = store.history(a)!!.lanes
    assertEquals(listOf(1000L, 2000L), lanes.map { it.start })
    assertEquals(100L, lanes.sumOf { it[0] })
  }

  @Test
  fun plus_missedSeconds_fillsLanesWithZero() {
    val history = SpeedHistory.of(100, START, mapOf(0L to 100L))
      .plus(40, START + 3.seconds, mapOf(0L to 40L))

    assertEquals(listOf(100L, 0L, 0L, 40L), history.lanes.single().toList())
  }

  @Test
  fun plus_laneGoneWithOnlyZeros_dropsIt() {
    val history = SpeedHistory.of(100, START, mapOf(0L to 100L, 500L to 0L))
      .plus(50, START + 1.seconds, mapOf(0L to 50L))

    assertEquals(listOf(0L), history.lanes.map { it.start })
  }

  @Test
  fun plus_laneGoneWithSamples_keepsItAtZero() {
    val history = SpeedHistory.of(100, START, mapOf(0L to 60L, 500L to 40L))
      .plus(50, START + 1.seconds, mapOf(0L to 50L))

    assertEquals(listOf(40L, 0L), history.lanes[1].toList())
  }

  @Test
  fun store_downloadingRow_tracksEachConnectionsRate() = runTest {
    val rows = MutableStateFlow(listOf(segmented(0, 0)))
    val store = SpeedHistoryStore(
      rows,
      backgroundScope,
      ListFixtures.clock(this),
      testScheduler.timeSource
    )
    runCurrent()

    for (second in 1..2) {
      rows.value = listOf(segmented(second * 100L, second * 50L))
      advanceTimeBy(1_000)
      runCurrent()
    }

    assertEquals(listOf(100L, 50L), store.rates(a).map { it.bytesPerSecond })
    rows.value = listOf(row("a", paused))
    runCurrent()
    assertTrue(store.rates(a).isEmpty())
  }

  @Test
  fun timelineEntries_taskAddedBeforeTheAppOpened_addsNothing() {
    val entries = timelineEntries(
      before = null,
      after = row("a", downloading(10), createdAt = START - 1.seconds),
      now = START + 5.seconds,
      since = START,
      started = false,
    )

    assertTrue(entries.isEmpty())
  }

  @Test
  fun timelineEntries_taskAddedSinceTheAppOpened_addsItAtCreation() = runTest {
    val created = START + 2.seconds

    val entries = timelineEntries(
      before = null,
      after = row("a", downloading(10), createdAt = created),
      now = START + 5.seconds,
      since = START,
      started = false,
    )

    assertEquals(listOf(created, START + 5.seconds), entries.map { it.at })
    assertEquals(listOf(TimelineKind.Added, TimelineKind.Started), entries.map { it.kind })
    assertEquals(listOf("Added", "Started"), entries.map { it.label }.load())
  }

  @Test
  fun timelineEntries_stateChanges_nameEachStep() = runTest {
    val queued = row("a", DownloadState.Queued)
    val running = row("a", downloading(10))
    val stopped = row("a", paused)
    val failed = row("a", DownloadState.Failed(KetchError.Http(403, "Forbidden")))

    assertEquals("Started", text(queued, running, started = false))
    assertEquals("Resumed", text(queued, running, started = true))
    assertEquals("Paused", text(running, stopped))
    assertEquals("Resumed", text(stopped, running))
    assertEquals("Failed · Access denied (403)", text(running, failed))
    assertEquals("Retried", text(failed, running))
  }

  @Test
  fun timelineEntries_requestChanges_describeEachChange() = runTest {
    val before = row("a", downloading(10), request = DownloadRequest("https://example.com/a"))
    val after = row(
      "a",
      downloading(10),
      request = before.request.copy(
        connections = 8,
        speedLimit = SpeedLimit.mbps(2),
        priority = DownloadPriority.URGENT,
      ),
    )

    val entries = timelineEntries(before, after, START, START, started = true)
    val texts = entries.map { it.label }.load()

    assertEquals(listOf("Connections Auto → 8", "Limit → 2 MB/s", "Priority → Urgent"), texts)
  }

  @Test
  fun timelineEntries_limitCleared_saysRemoved() = runTest {
    val limited = DownloadRequest("https://example.com/a", speedLimit = SpeedLimit.mbps(1))
    val before = row("a", downloading(10), request = limited)
    val after = row("a", downloading(10), request = limited.copy(speedLimit = SpeedLimit.Unlimited))

    assertEquals(
      listOf("Limit removed"),
      timelineEntries(before, after, START, START, started = true).map { it.label }.load()
    )
  }

  @Test
  fun observe_changesOverTime_appendToTheTimeline() = runTest {
    val rows = MutableStateFlow(emptyList<TaskRow>())
    val store = SpeedHistoryStore(rows, backgroundScope, ListFixtures.clock(this))
    val created = START + 1.seconds

    store.observe(listOf(row("a", DownloadState.Queued, createdAt = created)), created)
    store.observe(listOf(row("a", downloading(10), createdAt = created)), START + 2.seconds)
    store.observe(listOf(row("a", paused, createdAt = created)), START + 3.seconds)

    assertEquals(
      listOf("Added", "Started", "Paused"),
      store.timeline(a).map { it.label }.load()
    )
  }

  private suspend fun text(before: TaskRow, after: TaskRow, started: Boolean = true): String =
    timelineEntries(before, after, START, START, started).single().label.load()

  /** Row a downloading 2 000 bytes over two connections that have [first] and [second] bytes. */
  private fun segmented(first: Long, second: Long): TaskRow =
    row("a", downloading(first + second, total = 2000))
      .copy(segments = listOf(seg(0, 999, first), seg(1000, 1999, second)))

  private fun seg(start: Long, end: Long, downloaded: Long) =
    Segment(index = (start / 1000).toInt(), start = start, end = end, downloadedBytes = downloaded)

  /** A store whose rows list a and b, paused, so it only samples what tests record. */
  private fun TestScope.store(): SpeedHistoryStore {
    val rows = MutableStateFlow(listOf(row("a", paused), row("b", paused)))
    return SpeedHistoryStore(rows, backgroundScope, ListFixtures.clock(this))
  }
}
