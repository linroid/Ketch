package com.linroid.ketch.ai.fetch

import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class RateLimiterTest {

  private fun TestScope.limiter(delayMs: Long, maxConcurrent: Int = 3) =
    RateLimiter(delayMs = delayMs, maxConcurrent = maxConcurrent) {
      testScheduler.currentTime
    }

  @Test
  fun withPermit_sameHost_spacesStartsByDelay() = runTest {
    val limiter = limiter(delayMs = 1000)
    val starts = mutableListOf<Long>()

    List(3) {
      launch { limiter.withPermit("example.com") { starts += testScheduler.currentTime } }
    }.joinAll()

    assertEquals(listOf(0L, 1000L, 2000L), starts)
  }

  @Test
  fun withPermit_differentHosts_startTogether() = runTest {
    val limiter = limiter(delayMs = 1000)
    val starts = mutableListOf<Long>()

    listOf("a.example", "b.example", "c.example").map { host ->
      launch { limiter.withPermit(host) { starts += testScheduler.currentTime } }
    }.joinAll()

    assertEquals(listOf(0L, 0L, 0L), starts)
  }

  @Test
  fun withPermit_allSlotsBusy_waitsForRelease() = runTest {
    val limiter = limiter(delayMs = 0, maxConcurrent = 1)
    var secondStart = -1L

    listOf(
      launch { limiter.withPermit("a.example") { delay(500) } },
      launch { limiter.withPermit("b.example") { secondStart = testScheduler.currentTime } },
    ).joinAll()

    assertEquals(500L, secondStart)
  }
}
