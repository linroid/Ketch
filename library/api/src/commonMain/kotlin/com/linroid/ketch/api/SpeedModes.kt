package com.linroid.ketch.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.time.Instant

/**
 * Full speed, the Slow lane and Auto speed rules of an instance, which
 * `ketch.controller(SpeedModes)` returns where the instance lists [KetchFeatures.SPEED_MODES].
 * The instance applies them to its own global speed limit, so they hold while no client is
 * connected; [Interest.SpeedMode] follows them.
 */
interface SpeedModes {
  /** The settings and what they apply now. */
  suspend fun state(): SpeedModeState

  /**
   * Applies every field [change] sets at once and returns the state that results. Slow lane
   * speeds below [SpeedSettings.MIN_SLOW_LANE] are raised to it, and only the first
   * [SpeedSettings.MAX_RULES] rules are kept.
   */
  suspend fun update(change: SpeedModeChange): SpeedModeState

  companion object Key : ControlKey<SpeedModes> {
    override val name: String = "speedModes"
  }
}

/**
 * What [SpeedModes] apply now.
 *
 * @property settings the saved settings; [SpeedSettings.standard] is the standing cap
 * @property inSlowLane whether the slow lane holds the speed now, switched on by hand or by a rule
 * @property until in [SpeedLimitMode.Auto], when [inSlowLane] changes next; `null` when no rule
 *   changes it within a week, and outside Auto
 * @property limit the global speed limit in effect, as [DownloadConfig.speedLimit] reports it
 * @property slowLaneSpeed the speed of the slow lane: [SpeedSettings.slowLane], or
 *   [suggestedSlowLane]; the slow lane never runs faster than the standing cap
 * @property suggestedSlowLane slow lane speed suggested from [observedPeak]
 * @property observedPeak highest total download speed seen in the last week outside the slow
 *   lane, in bytes per second; `0` until there is history
 */
@Serializable
data class SpeedModeState(
  val settings: SpeedSettings = SpeedSettings(),
  val inSlowLane: Boolean = false,
  val until: Instant? = null,
  val limit: SpeedLimit = SpeedLimit.Unlimited,
  val slowLaneSpeed: SpeedLimit = SpeedSettings.DEFAULT_SLOW_LANE,
  val suggestedSlowLane: SpeedLimit = SpeedSettings.DEFAULT_SLOW_LANE,
  val observedPeak: Long = 0,
)

/**
 * A change of [SpeedModes]: the fields set replace the settings' own, the others stay.
 *
 * @property mode the mode to switch to
 * @property standard the standing cap, [SpeedLimit.Unlimited] for none
 * @property slowLane the slow lane speed; [SpeedLimit.Unlimited] follows the suggestion
 * @property rules the Auto rules, replacing every rule
 */
@Serializable
data class SpeedModeChange(
  val mode: SpeedLimitMode? = null,
  val standard: SpeedLimit? = null,
  val slowLane: SpeedLimit? = null,
  val rules: List<SpeedRule>? = null,
)

/** How an instance caps its total download speed. */
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
 * @property id value stored in `config.toml` and sent over the wire.
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
    PrimitiveSerialDescriptor("com.linroid.ketch.api.Weekday", PrimitiveKind.STRING)

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
 * [SpeedLimitMode.Auto]. Times are read in the time zone of the instance that applies them.
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
 * Speed modes of an instance ([SpeedModes]), persisted under `[speed]` of `config.toml` by the
 * apps and `ketch server`, whichever runs the downloads.
 *
 * @property mode which speed applies now.
 * @property standard standing cap; unlimited by default. The instance keeps it as its
 *   [DownloadConfig.speedLimit] in [SpeedLimitMode.Full], and the slow lane never runs faster.
 * @property slowLane slow lane speed; `null` uses a suggestion derived from the observed peak
 *   ([suggestSlowLane]).
 * @property rules weekly windows in which [SpeedLimitMode.Auto] turns the
 *   slow lane on; at most [MAX_RULES] are kept.
 */
@Serializable
data class SpeedSettings(
  val mode: SpeedLimitMode = SpeedLimitMode.Full,
  val standard: SpeedLimit = SpeedLimit.Unlimited,
  val slowLane: SpeedLimit? = null,
  val rules: List<SpeedRule> = emptyList(),
) {
  companion object {
    /** Most rules kept. */
    const val MAX_RULES: Int = 3

    /** Slowest the slow lane goes, 256 KB/s. */
    val MIN_SLOW_LANE: SpeedLimit = SpeedLimit.kbps(256)

    /** Slow lane speed until there is a peak to derive one from, 1 MB/s. */
    val DEFAULT_SLOW_LANE: SpeedLimit = SpeedLimit.mbps(1)

    /** Share of the observed peak the slow lane is suggested at. */
    const val SLOW_LANE_PERCENT: Int = 30

    /**
     * Suggests a slow lane speed: [SLOW_LANE_PERCENT] of [peakBytesPerSecond], at least
     * [MIN_SLOW_LANE], or [DEFAULT_SLOW_LANE] without a peak.
     */
    fun suggestSlowLane(peakBytesPerSecond: Long): SpeedLimit {
      if (peakBytesPerSecond <= 0) return DEFAULT_SLOW_LANE
      val speed = peakBytesPerSecond * SLOW_LANE_PERCENT / 100
      return SpeedLimit.of(maxOf(speed, MIN_SLOW_LANE.bytesPerSecond))
    }
  }
}
