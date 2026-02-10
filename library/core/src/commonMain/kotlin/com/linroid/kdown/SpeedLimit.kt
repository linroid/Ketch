package com.linroid.kdown

import kotlinx.serialization.Serializable
import kotlin.jvm.JvmInline

/**
 * Represents a download speed limit in bytes per second.
 *
 * Use [Unlimited] for no throttling, or factory methods [of], [kbps],
 * [mbps] to create a specific limit.
 *
 * @property bytesPerSecond the speed limit in bytes per second.
 *   `0` means unlimited.
 */
@Serializable
@JvmInline
value class SpeedLimit private constructor(val bytesPerSecond: Long) {

  /** Whether this speed limit is unlimited (no throttling). */
  val isUnlimited: Boolean get() = bytesPerSecond == 0L

  companion object {
    /** No speed limit. Downloads run at full speed. */
    val Unlimited = SpeedLimit(0)

    /**
     * Creates a speed limit of [bytesPerSecond] bytes per second.
     * @throws IllegalArgumentException if [bytesPerSecond] is not positive
     */
    fun of(bytesPerSecond: Long): SpeedLimit {
      require(bytesPerSecond > 0) { "bytesPerSecond must be positive" }
      return SpeedLimit(bytesPerSecond)
    }

    /**
     * Creates a speed limit of [kilobytesPerSecond] KB/s.
     * @throws IllegalArgumentException if [kilobytesPerSecond] is not positive
     */
    fun kbps(kilobytesPerSecond: Long): SpeedLimit =
      of(kilobytesPerSecond * 1024)

    /**
     * Creates a speed limit of [megabytesPerSecond] MB/s.
     * @throws IllegalArgumentException if [megabytesPerSecond] is not positive
     */
    fun mbps(megabytesPerSecond: Long): SpeedLimit =
      of(megabytesPerSecond * 1024 * 1024)
  }
}
