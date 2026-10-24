package com.linroid.ketch.core.speed

import com.linroid.ketch.api.SpeedModes
import com.linroid.ketch.api.SpeedSettings

/**
 * Highest total download speed seen recently, which the slow lane's suggested speed derives
 * from ([SpeedSettings.suggestSlowLane]).
 *
 * @property bytesPerSecond the speed; `0` until there is history.
 * @property atEpochMillis when it was seen, in epoch milliseconds.
 */
data class ObservedPeak(
  val bytesPerSecond: Long = 0,
  val atEpochMillis: Long = 0,
)

/**
 * Turns on [SpeedModes] for [com.linroid.ketch.core.Ketch]: the instance switches its global
 * speed limit between the standing cap and the slow lane, by hand or by weekly rules, and keeps
 * the highest speed it sees in a week outside the slow lane, which suggests the slow lane's
 * speed.
 *
 * The instance's [com.linroid.ketch.api.DownloadConfig.speedLimit] at start is the standing cap,
 * replacing [SpeedSettings.standard] of [settings]: save the cap as both, as [saveSettings] is
 * asked to.
 *
 * @property settings settings to start from, such as `[speed]` of `config.toml`.
 * @property observedPeak observed peak to start from, such as `[ui] observedPeak`.
 * @property saveSettings saves the settings after each change, before the change returns; save
 *   [SpeedSettings.standard] as the download speed limit too, as the instance starts from that.
 *   A failure is logged and the change kept.
 * @property savePeak saves the observed peak once it held for 10 seconds, as it climbs in steps
 *   while a download speeds up. A failure is logged.
 */
class SpeedModeOptions(
  val settings: SpeedSettings = SpeedSettings(),
  val observedPeak: ObservedPeak = ObservedPeak(),
  val saveSettings: suspend (SpeedSettings) -> Unit = {},
  val savePeak: suspend (ObservedPeak) -> Unit = {},
)
