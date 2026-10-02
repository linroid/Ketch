package com.linroid.ketch.app.ui.pulse

import com.linroid.ketch.app.state.SpeedHistory
import com.linroid.ketch.app.state.SpeedHistoryStore
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SpeedHistoryPopoverTest {

  private val end = Instant.parse("2026-10-01T14:30:00Z")

  @Test
  fun totalHistory_tasksSampledTogether_addsThemSecondBySecond() {
    val a = SpeedHistory(longArrayOf(1, 2, 3), end)
    val b = SpeedHistory(longArrayOf(10, 20), end)

    val total = totalHistory(listOf(a, b), end)

    assertEquals(end, total.end)
    assertEquals(SpeedHistoryStore.CAPACITY, total.samples.size)
    assertEquals(listOf(0L, 1L, 12L, 23L), total.samples.takeLast(4))
  }

  @Test
  fun totalHistory_taskThatStoppedEarlier_endsWhereItStopped() {
    val running = SpeedHistory(longArrayOf(5, 5, 5), end)
    val stopped = SpeedHistory(longArrayOf(7, 7), end - 2.seconds)

    val total = totalHistory(listOf(running, stopped), end + 1.seconds)

    assertEquals(listOf(0L, 7L, 12L, 5L, 5L), total.samples.takeLast(5))
  }

  @Test
  fun totalHistory_downloadsStoppedAWhileAgo_endsNow() {
    val stopped = SpeedHistory(longArrayOf(4, 4, 4), end)

    val recent = totalHistory(listOf(stopped), end + 60.seconds)
    val old = totalHistory(listOf(stopped), end + 10.minutes)

    assertEquals(end + 60.seconds, recent.end)
    assertEquals(listOf(4L, 4L, 4L) + List(60) { 0L }, recent.samples.takeLast(63))
    assertTrue(old.samples.all { it == 0L })
  }

  @Test
  fun totalHistory_noHistory_isEmpty() {
    val total = totalHistory(emptyList(), end)

    assertNull(total.end)
    assertEquals(emptyList(), total.samples)
  }
}
