package com.linroid.ketch.app.instance

import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.core.speed.ObservedPeak
import com.linroid.ketch.core.speed.SpeedModeOptions

/**
 * The speed modes the embedded engine runs, from `[speed]` and `[ui] observedPeak` of [config],
 * each change saved through [save], which applies a change to `config.toml` as it is now: the
 * settings with their standing cap as `[download] speedLimit`, which the engine starts from, and
 * the observed peak.
 */
fun embeddedSpeedModes(
  config: KetchConfig,
  save: suspend (transform: (KetchConfig) -> KetchConfig) -> Unit,
): SpeedModeOptions = SpeedModeOptions(
  settings = config.speed,
  observedPeak = ObservedPeak(config.ui.observedPeak, config.ui.observedPeakAt),
  saveSettings = { speed ->
    save { it.copy(speed = speed, download = it.download.copy(speedLimit = speed.standard)) }
  },
  savePeak = { peak ->
    save {
      it.copy(
        ui = it.ui.copy(observedPeak = peak.bytesPerSecond, observedPeakAt = peak.atEpochMillis),
      )
    }
  },
)
