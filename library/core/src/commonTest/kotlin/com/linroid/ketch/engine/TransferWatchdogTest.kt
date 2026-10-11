package com.linroid.ketch.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.engine.TransferStalledException
import com.linroid.ketch.core.engine.TransferWatchdog
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TransferWatchdogTest {
  @Test
  fun watch_noDataForIdleTimeout_failsWithRetryableNetworkError() = runTest {
    val watchdog = TransferWatchdog(idleTimeout = 10.seconds, minSpeed = 0)
    val error = assertFailsWith<KetchError.Network> {
      watchdog.watch { monitor ->
        monitor.received(100)
        awaitCancellation()
      }
    }
    assertIs<TransferStalledException>(error.cause)
    assertTrue(error.isRetryable)
    // Idle time is counted from the last check that saw data, one tick after it arrived.
    assertTrue(currentTime in 10_000L..11_000L, "stalled at $currentTime")
  }

  @Test
  fun watch_dataKeepsArriving_neverStalls() = runTest {
    val watchdog = TransferWatchdog(idleTimeout = 10.seconds, minSpeed = 0)
    val result = watchdog.watch { monitor ->
      repeat(10) {
        delay(9.seconds)
        monitor.received(1)
      }
      "done"
    }
    assertEquals("done", result)
  }

  @Test
  fun watch_longSpeedLimitWaits_doNotCountAsIdleOrSlow() = runTest {
    val watchdog = TransferWatchdog(
      idleTimeout = 5.seconds,
      minSpeed = 1_000,
      timeSource = testScheduler.timeSource,
    )
    watchdog.watch { monitor ->
      repeat(5) {
        monitor.received(100)
        monitor.throttled { delay(60.seconds) }
      }
    }
    assertEquals(300_000L, currentTime)
  }

  @Test
  fun watch_shortFrequentSpeedLimitWaits_areLeftOutOfTheAverage() = runTest {
    val watchdog = TransferWatchdog(
      idleTimeout = 5.seconds,
      minSpeed = 1_000,
      timeSource = testScheduler.timeSource,
    )
    // 100 bytes a second overall, but 1000 B/s while not waiting for the speed limit.
    watchdog.watch { monitor ->
      repeat(120) {
        monitor.received(100)
        monitor.throttled { delay(900.milliseconds) }
        delay(100.milliseconds)
      }
    }
    assertEquals(120_000L, currentTime)
  }

  @Test
  fun watch_belowMinSpeedOverWindow_fails() = runTest {
    val watchdog = TransferWatchdog(idleTimeout = 10.seconds, minSpeed = 1_000)
    val error = assertFailsWith<KetchError.Network> {
      watchdog.watch { monitor ->
        while (true) {
          monitor.received(500)
          delay(1.seconds)
        }
      }
    }
    assertIs<TransferStalledException>(error.cause)
    assertEquals(DownloadConfig.MIN_SPEED_WINDOW_MS, currentTime / 1000 * 1000)
  }

  @Test
  fun watch_atMinSpeed_passes() = runTest {
    val watchdog = TransferWatchdog(idleTimeout = Duration.ZERO, minSpeed = 1_000)
    watchdog.watch { monitor ->
      repeat(90) {
        monitor.received(1_000)
        delay(1.seconds)
      }
    }
    assertEquals(90_000L, currentTime)
  }

  @Test
  fun watch_failureOfTransfer_isNotReplaced() = runTest {
    val watchdog = TransferWatchdog(idleTimeout = 10.seconds, minSpeed = 0)
    assertFailsWith<KetchError.Disk> {
      watchdog.watch { throw KetchError.Disk(null) }
    }
  }

  @Test
  fun of_configWithBothOff_isDisabled() {
    val watchdog = TransferWatchdog.of(DownloadConfig(idleTimeoutMs = 0, minSpeed = 0))
    assertFalse(watchdog.isEnabled)
    assertTrue(TransferWatchdog.of(DownloadConfig.Default).isEnabled)
  }
}
