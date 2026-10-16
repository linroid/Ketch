package com.linroid.ketch.server

import java.net.Inet6Address
import java.net.InetAddress
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * Keeps devices from asking the owner again and again until a request is allowed by mistake.
 *
 * A request the owner turns down, or leaves unanswered, is a strike against its address: the
 * address waits [firstWait] before it may ask again, twice as long after each further strike, up
 * to [maxWait], until it has had none for [memory]. An IPv6 address counts as its /64 network,
 * as a device can take any address there. [pauseAfter] strikes from any addresses within
 * [pauseWindow] stop all requests for [pauseWindow], since many addresses are cheap too; the
 * owner can still share the pairing link.
 */
internal class PairingBackoff(
  private val firstWait: Duration = 30.seconds,
  private val maxWait: Duration = 1.hours,
  private val memory: Duration = 1.hours,
  private val pauseAfter: Int = 5,
  private val pauseWindow: Duration = 10.minutes,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  private class Strikes(var count: Int, var last: TimeMark)

  // Insertion ordered, so the oldest key goes first when too many are tracked.
  private val strikes = LinkedHashMap<String, Strikes>()
  private val recent = ArrayDeque<TimeMark>()
  private var pausedUntil: TimeMark? = null

  /** How long [address] must wait before it may ask; `null` when it may ask now. */
  @Synchronized
  fun waitFor(address: String): Duration? {
    pausedUntil?.let { until ->
      val left = -until.elapsedNow()
      if (left.isPositive()) return left
      pausedUntil = null
    }
    val key = keyOf(address)
    val entry = strikes[key] ?: return null
    val since = entry.last.elapsedNow()
    if (since >= memory) {
      strikes.remove(key)
      return null
    }
    val wait = waitAfter(entry.count) - since
    return wait.takeIf { it.isPositive() }
  }

  /** Records that the owner turned down, or did not answer, a request from [address]. */
  @Synchronized
  fun recordStrike(address: String) {
    val now = timeSource.markNow()
    val key = keyOf(address)
    val entry = strikes.remove(key)?.takeIf { it.last.elapsedNow() < memory }
    if (entry == null && strikes.size >= MAX_TRACKED) strikes.remove(strikes.keys.first())
    strikes[key] = (entry ?: Strikes(0, now)).apply {
      count++
      last = now
    }
    while (recent.firstOrNull()?.let { it.elapsedNow() >= pauseWindow } == true) {
      recent.removeFirst()
    }
    recent.addLast(now)
    if (recent.size >= pauseAfter) {
      pausedUntil = now + pauseWindow
      recent.clear()
    }
  }

  private fun waitAfter(count: Int): Duration {
    var wait = firstWait
    repeat(count - 1) {
      wait *= 2
      if (wait >= maxWait) return maxWait
    }
    return wait
  }

  private companion object {
    const val MAX_TRACKED = 1024

    /** [address] itself, or its /64 network for an IPv6 address. */
    fun keyOf(address: String): String {
      if (':' !in address) return address
      val bytes = try {
        (InetAddress.getByName(address.removeSurrounding("[", "]")) as? Inet6Address)?.address
      } catch (_: Exception) {
        null
      } ?: return address
      return bytes.take(8).joinToString(":", postfix = "::/64") { "%02x".format(it) }
    }
  }
}
