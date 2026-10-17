package com.linroid.ketch.segment

import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.segment.SegmentLedger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class SegmentLedgerTest {
  private val halves = listOf(
    Segment(index = 0, start = 0, end = 99),
    Segment(index = 1, start = 100, end = 199),
  )

  @Test
  fun steal_withoutRates_takesHalfOfTheLaneWithMostLeft() = runTest {
    val ledger = SegmentLedger(halves, minSplitBytes = 10)
    val (first, second) = ledger.initialLanes
    first.fill()
    val steal = assertNotNull(ledger.steal(first))
    assertSame(second, steal.victim)
    assertEquals(150L, steal.lane.start)
    assertEquals(199L, steal.lane.end)
    assertEquals(149L, second.end)
    assertEquals(
      listOf(0L..99L, 100L..149L, 150L..199L),
      ledger.snapshot().map { it.start..it.end },
    )
    assertEquals(listOf(0, 1, 2), ledger.snapshot().map { it.index })
  }

  @Test
  fun steal_neverMovesAdmittedBytes() = runTest {
    val ledger = SegmentLedger(halves, minSplitBytes = 10)
    val (first, second) = ledger.initialLanes
    first.fill()
    assertEquals(60, second.admit(60))
    val steal = assertNotNull(ledger.steal(first))
    // 40 bytes were not admitted yet; the victim keeps half of them.
    assertEquals(180L, steal.lane.start)
    assertEquals(179L, second.end)
    assertFalse(steal.victimIdle)
  }

  @Test
  fun steal_tailBelowMinimum_isRefused() = runTest {
    val ledger = SegmentLedger(halves, minSplitBytes = 60)
    val (first, _) = ledger.initialLanes
    first.fill()
    assertNull(ledger.steal(first), "Half of 100 bytes is below the 60-byte minimum")
  }

  @Test
  fun steal_neverSplitsWithoutRangeSupport() = runTest {
    val ledger = SegmentLedger(halves, minSplitBytes = Long.MAX_VALUE)
    val (first, _) = ledger.initialLanes
    first.fill()
    assertNull(ledger.steal(first))
  }

  @Test
  fun cutLane_endsItsTransferAtItsNewEnd() = runTest {
    val ledger = SegmentLedger(halves, minSplitBytes = 10)
    val (first, second) = ledger.initialLanes
    first.fill()
    assertNotNull(ledger.steal(first))
    assertEquals(40, second.admit(40))
    second.commit(40)
    // Ten bytes are left; a chunk of 30 only gets those, and writing them ends the transfer.
    assertEquals(10, second.admit(30))
    assertFailsWith<CancellationException> { second.commit(10) }
    assertTrue(second.isComplete)
    assertFailsWith<CancellationException> { second.admit(1) }
  }

  @Test
  fun uncutLane_atItsEnd_admitsNothingWithoutStopping() = runTest {
    val ledger = SegmentLedger(halves, minSplitBytes = 10)
    val first = ledger.initialLanes.first()
    first.fill()
    assertEquals(0, first.admit(10))
  }

  @Test
  fun steal_withRates_splitsWhereBothFinishTogether() = runTest {
    val time = TestTimeSource()
    val ledger = SegmentLedger(
      listOf(
        Segment(index = 0, start = 0, end = 2_999),
        Segment(index = 1, start = 3_000, end = 4_999),
      ),
      minSplitBytes = 10,
      timeSource = time,
      setupTime = Duration.ZERO,
    )
    val (fast, slow) = ledger.initialLanes
    time += 1.seconds
    fast.fill(3_000) // 3000 B/s
    slow.fill(1_000) // 1000 B/s, 1000 bytes left
    val steal = assertNotNull(ledger.steal(fast))
    // The slow lane keeps a quarter: 250 bytes take it as long as 750 take the fast one.
    assertEquals(4_250L, steal.lane.start)
    assertEquals(4_249L, slow.end)
  }

  @Test
  fun steal_fromLaneReceivingNothing_takesEverythingNotAdmitted() = runTest {
    val time = TestTimeSource()
    val ledger = SegmentLedger(halves, minSplitBytes = 10, timeSource = time)
    val (first, second) = ledger.initialLanes
    time += 2.seconds
    first.fill()
    val steal = assertNotNull(ledger.steal(first))
    assertEquals(100L, steal.lane.start)
    assertTrue(second.isComplete)
    assertTrue(steal.victimIdle, "A victim left with nothing is stopped at once")
  }

  @Test
  fun steal_whenSplittingWouldNotFinishSooner_isRefused() = runTest {
    val time = TestTimeSource()
    val ledger = SegmentLedger(
      halves,
      minSplitBytes = 10,
      timeSource = time,
      setupTime = 10.seconds,
    )
    val (first, second) = ledger.initialLanes
    time += 1.seconds
    first.fill()
    second.fill(50) // 50 bytes left at 50 B/s: one second, far less than a new request's setup
    assertNull(ledger.steal(first))
  }
}
