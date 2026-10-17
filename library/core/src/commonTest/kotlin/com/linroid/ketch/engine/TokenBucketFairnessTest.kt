package com.linroid.ketch.engine

import com.linroid.ketch.core.engine.TokenBucket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Runs on virtual time: the bucket reads the test scheduler's clock. Kept to a few thousand turns
 * a test, as JS runs each test within a 2 s timeout.
 */
class TokenBucketFairnessTest {
  @Test
  fun acquire_ownersShareTheRateEquallyWhateverTheirCallers() = runTest {
    val bucket = bucket(RATE)
    val many = Counter()
    val one = Counter()
    repeat(10) { backgroundScope.charge(bucket, "many", BLOCK, many) }
    backgroundScope.charge(bucket, "one", BLOCK, one)

    advanceTimeBy(10.seconds)

    // One shared limit once gave the task waiting with one caller 1/11 of it.
    assertShare(0.5, one.bytes, many.bytes)
    assertNear(RATE * 10, many.bytes + one.bytes)
  }

  @Test
  fun acquire_smallRequests_getAnEqualShareOfBytes() = runTest {
    val bucket = bucket(RATE)
    val large = Counter()
    val small = Counter()
    repeat(10) { backgroundScope.charge(bucket, "large", BLOCK, large) }
    backgroundScope.charge(bucket, "small", 1024, small)

    advanceTimeBy(10.seconds)

    assertShare(0.5, small.bytes, large.bytes)
  }

  @Test
  fun acquire_threeOwners_shareInThirds() = runTest {
    val bucket = bucket(RATE)
    val counters = List(3) { Counter() }
    counters.forEachIndexed { index, counter ->
      repeat(index * 4 + 1) { backgroundScope.charge(bucket, index, BLOCK, counter) }
    }

    advanceTimeBy(15.seconds)

    val total = counters.sumOf { it.bytes }
    for (counter in counters) assertShare(1.0 / 3, counter.bytes, total - counter.bytes)
  }

  @Test
  fun acquire_ownerJoiningLate_getsItsShareAndNoCatchUp() = runTest {
    val bucket = bucket(RATE)
    val early = Counter()
    val late = Counter()
    repeat(10) { backgroundScope.charge(bucket, "early", BLOCK, early) }
    advanceTimeBy(10.seconds)
    val earlyBefore = early.bytes

    backgroundScope.charge(bucket, "late", BLOCK, late)
    advanceTimeBy(3.seconds)
    // At once, a newcomer gets half, not what the other had over ten seconds.
    assertShare(0.5, late.bytes, early.bytes - earlyBefore)
  }

  @Test
  fun acquire_ownerThatPauses_doesNotSaveItsTurns() = runTest {
    val bucket = bucket(RATE)
    val steady = Counter()
    val paused = Counter()
    repeat(4) { backgroundScope.charge(bucket, "steady", BLOCK, steady) }
    val pausing = backgroundScope.charge(bucket, "paused", BLOCK, paused)
    advanceTimeBy(5.seconds)
    pausing.cancel()
    advanceTimeBy(5.seconds)

    val steadyBefore = steady.bytes
    val pausedBefore = paused.bytes
    backgroundScope.charge(bucket, "paused", BLOCK, paused)
    advanceTimeBy(3.seconds)

    assertShare(0.5, paused.bytes - pausedBefore, steady.bytes - steadyBefore)
  }

  @Test
  fun acquire_oneOwnersCallers_keepTheRate() = runTest {
    val bucket = bucket(RATE)
    val counter = Counter()
    repeat(16) { backgroundScope.charge(bucket, "task", BLOCK, counter) }

    advanceTimeBy(10.seconds)

    assertNear(RATE * 10, counter.bytes)
  }

  @Test
  fun acquire_largerThanBurst_isServedInTurns() = runTest {
    val bucket = bucket(1000, burstSize = 50)
    var done = false
    backgroundScope.launch {
      bucket.acquire(50)
      bucket.acquire(200)
      done = true
    }

    advanceTimeBy(190.milliseconds)
    assertFalse(done)
    advanceTimeBy(20.milliseconds)
    assertTrue(done)
  }

  @Test
  fun updateRate_unlimited_releasesEveryWaiter() = runTest {
    val bucket = bucket(1000, burstSize = 100)
    bucket.acquire(100)
    var released = 0
    repeat(3) { index ->
      backgroundScope.launch {
        bucket.acquire(10_000, owner = index % 2)
        released++
      }
    }
    advanceTimeBy(1.seconds)
    assertTrue(released == 0)

    bucket.updateRate(0)
    runCurrent()

    assertTrue(released == 3)
  }

  @Test
  fun acquire_cancelledFirstWaiter_letsTheNextOneThrough() = runTest {
    val bucket = bucket(1000, burstSize = 100)
    bucket.acquire(100)
    val first = backgroundScope.launch { bucket.acquire(50_000) }
    var nextDone = false
    backgroundScope.launch {
      bucket.acquire(50)
      nextDone = true
    }
    advanceTimeBy(10.milliseconds)

    first.cancel()
    advanceTimeBy(100.milliseconds)

    assertTrue(nextDone)
  }

  private fun TestScope.bucket(rate: Long, burstSize: Long = 65536) =
    TokenBucket(rate, burstSize, testScheduler.timeSource)

  private fun CoroutineScope.charge(
    bucket: TokenBucket,
    owner: Any,
    bytes: Int,
    counter: Counter,
  ): Job = launch {
    while (true) {
      bucket.acquire(bytes, owner)
      counter.bytes += bytes
    }
  }

  private fun assertShare(expected: Double, bytes: Long, others: Long) {
    val share = bytes.toDouble() / (bytes + others)
    assertTrue(share in expected - 0.05..expected + 0.05, "Expected a share of $expected: $share")
  }

  private fun assertNear(expected: Long, actual: Long) {
    // Plus the burst, give or take a turn.
    val slack = 65_536L + BLOCK
    assertTrue(actual in expected - slack..expected + slack, "Expected $expected: $actual")
  }

  private class Counter {
    var bytes = 0L
  }

  private companion object {
    const val RATE = 200_000L
    const val BLOCK = 16_384
  }
}
