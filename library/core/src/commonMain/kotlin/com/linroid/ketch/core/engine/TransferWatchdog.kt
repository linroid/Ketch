@file:OptIn(ExperimentalAtomicApi::class)

package com.linroid.ketch.core.engine

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchError
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.AtomicLong
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.decrementAndFetch
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * Drops transfers that stall: [watch] fails a transfer with a retryable [KetchError.Network]
 * when it receives no data for [idleTimeout], or averages less than [minSpeed] bytes per second
 * over [speedWindow]. Time a transfer spends in [TransferMonitor.throttled], waiting for a speed
 * limit, counts as neither.
 *
 * The watchdog checks the transfer every [tick] with [delay], so it follows the virtual time of
 * coroutine tests, and measures speed limit waits with [timeSource]. A check during a wait does
 * not count at all; a check after waits leaves their time out of the average. A transfer whose
 * limits are both off runs without a watchdog.
 *
 * @param idleTimeout how long a transfer may receive nothing, [Duration.ZERO] for no limit
 * @param minSpeed the lowest average speed in bytes per second, `0` for none
 * @param speedWindow the span [minSpeed] is averaged over
 * @param timeSource measures how long speed limit waits take
 */
class TransferWatchdog(
  val idleTimeout: Duration,
  val minSpeed: Long,
  val speedWindow: Duration = DownloadConfig.MIN_SPEED_WINDOW_MS.milliseconds,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  init {
    require(!idleTimeout.isNegative()) { "idleTimeout must not be negative" }
    require(minSpeed >= 0) { "minSpeed must not be negative" }
    require(speedWindow.isPositive()) { "speedWindow must be positive" }
  }

  /** Whether the watchdog checks anything. */
  val isEnabled: Boolean get() = idleTimeout.isPositive() || minSpeed > 0

  /** How often the transfer is checked: a tenth of [idleTimeout], from 10 ms to one second. */
  internal val tick: Duration =
    if (idleTimeout.isPositive()) (idleTimeout / 10).coerceIn(10.milliseconds, 1.seconds)
    else 1.seconds

  /**
   * Runs [block], one transfer, under the watchdog. [block] reports every chunk it receives with
   * [TransferMonitor.received] and runs its speed limit waits in [TransferMonitor.throttled].
   * A stall cancels [block] and throws [KetchError.Network], whose cause names the stall.
   */
  suspend fun <T> watch(block: suspend (TransferMonitor) -> T): T {
    if (!isEnabled) return block(TransferMonitor.None)
    val monitor = Monitor(timeSource)
    return try {
      coroutineScope {
        val checker = launch { check(monitor) }
        try {
          block(monitor)
        } finally {
          checker.cancel()
        }
      }
    } catch (e: TransferStalledException) {
      throw KetchError.Network(e)
    }
  }

  private suspend fun check(monitor: Monitor) {
    var idle = Duration.ZERO
    var windowTime = Duration.ZERO
    var windowBytes = 0L
    while (true) {
      delay(tick)
      val bytes = monitor.bytes.exchange(0)
      val waited = monitor.waitedNanos.exchange(0).nanoseconds
      val waiting = monitor.waiting.load() > 0
      idle = if (bytes > 0 || waiting || waited.isPositive()) Duration.ZERO else idle + tick
      if (idleTimeout.isPositive() && idle >= idleTimeout) {
        throw TransferStalledException("No data received for $idleTimeout")
      }
      if (minSpeed <= 0) continue
      // A wait that spans checks is left out whole: the check during it counts no time, and
      // its full length comes off the time of the check after it.
      windowTime += if (waiting) Duration.ZERO else (tick - waited).coerceAtLeast(Duration.ZERO)
      windowBytes += bytes
      if (windowTime >= speedWindow) {
        val speed = windowBytes * 1000 / windowTime.inWholeMilliseconds
        if (speed < minSpeed) {
          throw TransferStalledException(
            "Averaged $speed B/s over $windowTime, below the minimum of $minSpeed B/s",
          )
        }
        windowTime = Duration.ZERO
        windowBytes = 0
      }
    }
  }

  private class Monitor(private val timeSource: TimeSource) : TransferMonitor {
    val bytes = AtomicLong(0)
    val waiting = AtomicInt(0)
    val waitedNanos = AtomicLong(0)

    override fun received(bytes: Int) {
      this.bytes.addAndFetch(bytes.toLong())
    }

    override suspend fun <T> throttled(wait: suspend () -> T): T {
      val start = timeSource.markNow()
      waiting.incrementAndFetch()
      try {
        return wait()
      } finally {
        waiting.decrementAndFetch()
        waitedNanos.addAndFetch(start.elapsedNow().inWholeNanoseconds)
      }
    }
  }

  companion object {
    /** A watchdog that never fails a transfer. */
    val Off: TransferWatchdog = TransferWatchdog(Duration.ZERO, 0)

    /** The watchdog [DownloadConfig.idleTimeoutMs] and [DownloadConfig.minSpeed] set. */
    fun of(config: DownloadConfig): TransferWatchdog =
      TransferWatchdog(config.idleTimeoutMs.milliseconds, config.minSpeed)
  }
}

/** What a transfer tells its [TransferWatchdog]. */
interface TransferMonitor {
  /** Counts [bytes] received from the network. */
  fun received(bytes: Int)

  /** Runs [wait], a speed limit wait, which the watchdog does not count as a stall. */
  suspend fun <T> throttled(wait: suspend () -> T): T

  /** A monitor no watchdog reads. */
  object None : TransferMonitor {
    override fun received(bytes: Int) = Unit
    override suspend fun <T> throttled(wait: suspend () -> T): T = wait()
  }
}

/** The cause of the [KetchError.Network] a stalled transfer fails with. */
class TransferStalledException(message: String) : Exception(message)
