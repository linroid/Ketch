package com.linroid.ketch.config

import com.linroid.ketch.api.SpeedLimit
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/** How the apps cap the total download speed. */
@Serializable
enum class SpeedLimitMode {
  /** Downloads run at [SpeedSettings.standard], which is unlimited unless a cap is set. */
  @SerialName("full")
  Full,

  /** Downloads run at the slow lane speed until switched back. */
  @SerialName("slow-lane")
  SlowLane,

  /** The slow lane applies while one of [SpeedSettings.rules] is in effect. */
  @SerialName("auto")
  Auto,
}

/**
 * A day of the week, Monday first as in ISO 8601.
 *
 * @property id value stored in `config.toml`.
 */
@Serializable(with = WeekdaySerializer::class)
enum class Weekday(val id: String) {
  Monday("mon"),
  Tuesday("tue"),
  Wednesday("wed"),
  Thursday("thu"),
  Friday("fri"),
  Saturday("sat"),
  Sunday("sun"),
}

// ktoml cannot decode enums inside arrays, so days are written as plain strings.
internal object WeekdaySerializer : KSerializer<Weekday> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.config.Weekday", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: Weekday) {
    encoder.encodeString(value.id)
  }

  override fun deserialize(decoder: Decoder): Weekday {
    val id = decoder.decodeString()
    return Weekday.entries.firstOrNull { it.id == id }
      ?: throw IllegalArgumentException("Invalid day: '$id'. Use mon, tue, ... sun.")
  }
}

/**
 * Turns the slow lane on during a weekly time window while the mode is
 * [SpeedLimitMode.Auto].
 *
 * @property days days the window starts on; empty means every day.
 * @property start local start time as `HH:MM`.
 * @property end local end time as `HH:MM`; a time before [start] ends the
 *   window on the next day.
 */
@Serializable
data class SpeedRule(
  val days: Set<Weekday> = emptySet(),
  val start: String = "09:00",
  val end: String = "18:00",
)

/**
 * Speed modes for the embedded device, persisted under `[speed]`.
 *
 * Only the apps read this section; the CLI and server ignore it.
 *
 * @property mode which speed applies now.
 * @property standard standing cap in [SpeedLimitMode.Full]; unlimited by default.
 * @property slowLane slow lane speed; `null` uses a suggestion derived from
 *   [UiPreferences.observedPeak].
 * @property rules weekly windows in which [SpeedLimitMode.Auto] turns the
 *   slow lane on; the apps offer at most [MAX_RULES].
 */
@Serializable
data class SpeedSettings(
  val mode: SpeedLimitMode = SpeedLimitMode.Full,
  val standard: SpeedLimit = SpeedLimit.Unlimited,
  val slowLane: SpeedLimit? = null,
  val rules: List<SpeedRule> = emptyList(),
) {
  companion object {
    /** Most rules the apps let the user add. */
    const val MAX_RULES: Int = 3
  }
}
