package com.linroid.ketch.api

import com.linroid.ketch.api.SpeedLimit.Companion.Unlimited
import com.linroid.ketch.api.SpeedLimit.Companion.kbps
import com.linroid.ketch.api.SpeedLimit.Companion.mbps
import com.linroid.ketch.api.SpeedLimit.Companion.of
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.jvm.JvmInline

/**
 * Represents a download speed limit in bytes per second.
 *
 * Use [Unlimited] for no throttling, or factory methods [of], [kbps],
 * [mbps] to create a specific limit.
 *
 * Serializes as a human-readable string: `"10m"` (MB/s), `"500k"`
 * (KB/s), raw bytes, or `"unlimited"`.
 *
 * @property bytesPerSecond the speed limit in bytes per second.
 *   `0` means unlimited.
 */
@Serializable(with = SpeedLimitSerializer::class)
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
     * @throws IllegalArgumentException if [kilobytesPerSecond] is not positive, or so large that
     *   its bytes per second overflow a [Long]
     */
    fun kbps(kilobytesPerSecond: Long): SpeedLimit {
      require(kilobytesPerSecond <= Long.MAX_VALUE / KB) { "kilobytesPerSecond is too large" }
      return of(kilobytesPerSecond * KB)
    }

    /**
     * Creates a speed limit of [megabytesPerSecond] MB/s.
     * @throws IllegalArgumentException if [megabytesPerSecond] is not positive, or so large that
     *   its bytes per second overflow a [Long]
     */
    fun mbps(megabytesPerSecond: Long): SpeedLimit {
      require(megabytesPerSecond <= Long.MAX_VALUE / MB) { "megabytesPerSecond is too large" }
      return of(megabytesPerSecond * MB)
    }

    /**
     * Parses a speed limit string with optional suffix.
     *
     * Accepted formats:
     * - `"unlimited"` — no throttling
     * - `"10m"` — 10 MB/s
     * - `"500k"` — 500 KB/s
     * - `"1048576"` — raw bytes per second
     *
     * @return the parsed [SpeedLimit], or `null` if the value is invalid, including a rate
     *   too large for a [Long] of bytes per second.
     */
    fun parse(value: String): SpeedLimit? {
      val trimmed = value.trim().lowercase()
      if (trimmed == "unlimited") return Unlimited
      return when {
        trimmed.endsWith("m") -> {
          val num = trimmed.dropLast(1).toLongOrNull() ?: return null
          if (num <= 0 || num > Long.MAX_VALUE / MB) return null
          mbps(num)
        }
        trimmed.endsWith("k") -> {
          val num = trimmed.dropLast(1).toLongOrNull() ?: return null
          if (num <= 0 || num > Long.MAX_VALUE / KB) return null
          kbps(num)
        }
        else -> {
          val num = trimmed.toLongOrNull() ?: return null
          if (num <= 0) return null
          of(num)
        }
      }
    }

    /**
     * Formats a [SpeedLimit] as a human-readable string.
     *
     * Uses the most compact representation: `"10m"` for whole MB/s,
     * `"500k"` for whole KB/s, raw bytes otherwise,
     * or `"unlimited"`.
     */
    internal fun format(limit: SpeedLimit): String {
      if (limit.isUnlimited) return "unlimited"
      val bytes = limit.bytesPerSecond
      return when {
        bytes % MB == 0L -> "${bytes / MB}m"
        bytes % KB == 0L -> "${bytes / KB}k"
        else -> "$bytes"
      }
    }

    private const val KB = 1024L
    private const val MB = 1024L * 1024L
  }

  override fun toString(): String {
    return format(this)
  }
}

internal object SpeedLimitSerializer : KSerializer<SpeedLimit> {
  override val descriptor = PrimitiveSerialDescriptor(
    "SpeedLimit", PrimitiveKind.STRING,
  )

  override fun serialize(encoder: Encoder, value: SpeedLimit) {
    encoder.encodeString(SpeedLimit.format(value))
  }

  override fun deserialize(decoder: Decoder): SpeedLimit {
    val raw = decoder.decodeString()
    return SpeedLimit.parse(raw)
      ?: throw IllegalArgumentException(
        "Invalid speed limit: '$raw'. " +
          "Use 'unlimited', '10m', '500k', or raw bytes.",
      )
  }
}
