package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
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

  /** A store whose rows list a and b, paused, so it only samples what tests record. */
  private fun TestScope.store(): SpeedHistoryStore {
    val rows = MutableStateFlow(listOf(row("a", paused), row("b", paused)))
    return SpeedHistoryStore(rows, backgroundScope, ListFixtures.clock(this))
  }
}
