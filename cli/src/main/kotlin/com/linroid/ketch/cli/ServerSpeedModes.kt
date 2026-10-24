package com.linroid.ketch.cli

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SpeedLimitMode
import com.linroid.ketch.api.SpeedSettings
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.core.speed.ObservedPeak
import com.linroid.ketch.core.speed.SpeedModeOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * The speed modes `ketch server` runs, from `[speed]` and `[ui] observedPeak` of [saved], the
 * config file as it was read, and saved back to [store] as they change, so clients such as a
 * phone switch them and the server follows them on its own.
 *
 * The standing cap goes to `[download] speedLimit`, which the server starts from, only once it
 * differs from [startCap], the cap the server started with, which may come from a flag or an
 * environment variable rather than the file.
 */
internal fun serverSpeedModes(
  store: ConfigStore,
  saved: KetchConfig,
  startCap: SpeedLimit,
): SpeedModeOptions {
  val lock = Mutex()
  var savedCap = startCap
  suspend fun save(transform: (KetchConfig) -> KetchConfig) {
    lock.withLock {
      withContext(Dispatchers.IO) { store.save(transform(store.load())) }
    }
  }
  return SpeedModeOptions(
    settings = saved.speed,
    observedPeak = ObservedPeak(saved.ui.observedPeak, saved.ui.observedPeakAt),
    saveSettings = { speed ->
      val cap = speed.standard.takeIf { it != savedCap }
      save { config ->
        val download = cap?.let { config.download.copy(speedLimit = it) } ?: config.download
        config.copy(speed = speed, download = download)
      }
      if (cap != null) savedCap = cap
    },
    savePeak = { peak ->
      save { config ->
        config.copy(
          ui = config.ui.copy(
            observedPeak = peak.bytesPerSecond,
            observedPeakAt = peak.atEpochMillis,
          ),
        )
      }
    },
  )
}

/** The speed mode of [settings] as the server's banner names it. */
internal fun describeSpeedMode(settings: SpeedSettings): String = when (settings.mode) {
  SpeedLimitMode.Full -> "full speed"
  SpeedLimitMode.SlowLane -> "slow lane"
  SpeedLimitMode.Auto -> when (val rules = settings.rules.size) {
    0 -> "auto, no rules"
    1 -> "auto, 1 rule"
    else -> "auto, $rules rules"
  }
}
