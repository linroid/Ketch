package com.linroid.kdown.engine

import com.linroid.kdown.core.engine.ServerInfo
import com.linroid.kdown.core.engine.SpeedLimiter
import com.linroid.kdown.core.engine.TokenBucket
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.measureTime

class SpeedLimitIntegrationTest {

  // All tests use Dispatchers.Default because TokenBucket relies on
  // Clock.System.now() for refill timing, which requires real wall-clock
  // time rather than runTest's virtual time.

  @Test
  fun taskLimiter_throttlesDataFlow() = runTest {
    withContext(Dispatchers.Default) {
      val limiter = TokenBucket(
        bytesPerSecond = 1000,
        burstSize = 200
      )

      val totalBytes = 2000
      val engine = FakeHttpEngine(
        serverInfo = ServerInfo(
          totalBytes.toLong(), true, null, null
        ),
        content = ByteArray(totalBytes),
        chunkSize = 100
      )

      val elapsed = measureTime {
        engine.download(
          "https://example.com/file",
          0L..totalBytes.toLong() - 1
        ) { data ->
          limiter.acquire(data.size)
        }
      }

      // 2000 bytes at 1000 B/s with 200 burst takes >1 second
      assertTrue(
        elapsed >= 1.seconds,
        "Expected throttle for 2000B at 1000B/s, got $elapsed"
      )
    }
  }

  @Test
  fun globalLimiter_throttlesAcrossSegments() = runTest {
    withContext(Dispatchers.Default) {
      val globalLimiter = TokenBucket(
        bytesPerSecond = 2000,
        burstSize = 200
      )

      val engine = FakeHttpEngine(
        serverInfo = ServerInfo(1000, true, null, null),
        content = ByteArray(1000),
        chunkSize = 100
      )

      val elapsed = measureTime {
        val job1 = async {
          engine.download(
            "https://example.com/file", 0L..499L
          ) { data ->
            globalLimiter.acquire(data.size)
          }
        }
        val job2 = async {
          engine.download(
            "https://example.com/file", 500L..999L
          ) { data ->
            globalLimiter.acquire(data.size)
          }
        }

        job1.await()
        job2.await()
      }

      // 1000 bytes total through shared 2000 B/s limiter
      // with small burst should produce measurable throttling
      assertTrue(
        elapsed >= 100.milliseconds,
        "Expected global throttle across segments, got $elapsed"
      )
    }
  }

  @Test
  fun bothLimiters_moreRestrictiveWins() = runTest {
    withContext(Dispatchers.Default) {
      val taskLimiter = TokenBucket(
        bytesPerSecond = 5000,
        burstSize = 100
      )
      val globalLimiter = TokenBucket(
        bytesPerSecond = 500,
        burstSize = 100
      )

      val engine = FakeHttpEngine(
        serverInfo = ServerInfo(500, true, null, null),
        content = ByteArray(500),
        chunkSize = 100
      )

      val elapsed = measureTime {
        engine.download(
          "https://example.com/file", 0L..499L
        ) { data ->
          taskLimiter.acquire(data.size)
          globalLimiter.acquire(data.size)
        }
      }

      // 500 bytes at effective 500 B/s (more restrictive) ~0.8s
      assertTrue(
        elapsed >= 500.milliseconds,
        "Expected more restrictive limit to apply, got $elapsed"
      )
    }
  }

  @Test
  fun unlimitedLimiters_noThrottleOverhead() = runTest {
    val taskLimiter = SpeedLimiter.Unlimited
    val globalLimiter = SpeedLimiter.Unlimited

    val engine = FakeHttpEngine(
      serverInfo = ServerInfo(10000, true, null, null),
      content = ByteArray(10000),
      chunkSize = 1000
    )

    val elapsed = measureTime {
      engine.download(
        "https://example.com/file", 0L..9999L
      ) { data ->
        taskLimiter.acquire(data.size)
        globalLimiter.acquire(data.size)
      }
    }

    assertTrue(
      elapsed < 500.milliseconds,
      "Expected no throttle overhead when unlimited, got $elapsed"
    )
  }

  @Test
  fun dynamicAdjustment_speedUp() = runTest {
    withContext(Dispatchers.Default) {
      val limiter = TokenBucket(
        bytesPerSecond = 200,
        burstSize = 50
      )

      val engine = FakeHttpEngine(
        serverInfo = ServerInfo(500, true, null, null),
        content = ByteArray(500),
        chunkSize = 50
      )

      var chunksReceived = 0

      withTimeout(10.seconds) {
        engine.download(
          "https://example.com/file", 0L..499L
        ) { data ->
          limiter.acquire(data.size)
          chunksReceived++
          // After 2 chunks (100 bytes), increase speed
          if (chunksReceived == 2) {
            limiter.updateRate(1_000_000)
          }
        }
      }

      assertTrue(
        chunksReceived == 10,
        "Expected all 10 chunks received, got $chunksReceived"
      )
    }
  }

  @Test
  fun dynamicAdjustment_slowDown() = runTest {
    withContext(Dispatchers.Default) {
      val limiter = TokenBucket(
        bytesPerSecond = 1_000_000,
        burstSize = 100
      )

      val engine = FakeHttpEngine(
        serverInfo = ServerInfo(500, true, null, null),
        content = ByteArray(500),
        chunkSize = 50
      )

      var chunksReceived = 0
      val elapsed = measureTime {
        engine.download(
          "https://example.com/file", 0L..499L
        ) { data ->
          limiter.acquire(data.size)
          chunksReceived++
          // After 2 chunks, slow down drastically
          if (chunksReceived == 2) {
            limiter.updateRate(500)
          }
        }
      }

      // After slowing to 500 B/s, remaining 400 bytes ~0.6s+
      assertTrue(
        elapsed >= 200.milliseconds,
        "Expected slowdown after dynamic adjustment, got $elapsed"
      )
    }
  }
}
