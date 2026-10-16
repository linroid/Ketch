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
 * A speed limit: [Unlimited], or one made with [of], [kbps] or [mbps]. Serialized as `10m`
 * (MiB/s), `500k` (KiB/s), bytes, or `unlimited`.
 *
 * @property bytesPerSecond the limit, `0` for unlimited
 */
@Serializable(with = SpeedLimitSerializer::class)
@JvmInline
value class SpeedLimit private constructor(val bytesPerSecond: Long) {

  /** Whether there is no limit. */
  val isUnlimited: Boolean get() = bytesPerSecond == 0L

  companion object {
    /** No limit. */
    val Unlimited = SpeedLimit(0)

    /** @throws IllegalArgumentException if [bytesPerSecond] is not positive */
    fun of(bytesPerSecond: Long): SpeedLimit {
      require(bytesPerSecond > 0) { "bytesPerSecond must be positive" }
      return SpeedLimit(bytesPerSecond)
    }

    /** @throws IllegalArgumentException if not positive, or too large for a [Long] of bytes */
    fun kbps(kilobytesPerSecond: Long): SpeedLimit {
      require(kilobytesPerSecond <= Long.MAX_VALUE / KB) { "kilobytesPerSecond is too large" }
      return of(kilobytesPerSecond * KB)
    }

    /** @throws IllegalArgumentException if not positive, or too large for a [Long] of bytes */
    fun mbps(megabytesPerSecond: Long): SpeedLimit {
      require(megabytesPerSecond <= Long.MAX_VALUE / MB) { "megabytesPerSecond is too large" }
      return of(megabytesPerSecond * MB)
    }

    /** Reads `unlimited`, `10m`, `500k` or bytes per second; `null` if invalid or too large. */
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

    /** The shortest form [parse] reads. */
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
