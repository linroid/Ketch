package com.linroid.ketch.app

import com.linroid.ketch.api.ControlKey
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.SpeedModes
import com.linroid.ketch.api.SpeedSettings
import kotlinx.coroutines.flow.Flow
import kotlin.time.Clock
import kotlin.time.Duration

/**
 * An embedded engine that keeps the download config it is given and runs [speedModes] over its
 * speed limit, as `Ketch` does; without speed [settings] it runs none.
 */
class SpeedModeEngine(
  settings: SpeedSettings? = SpeedSettings(),
  clock: Clock = Clock.System,
  private val base: KetchApi = FakeKetchApi("Core"),
) : KetchApi by base {
  /** The config it runs with; its speed limit is the one the speed modes hold. */
  var config = DownloadConfig(speedLimit = settings?.standard ?: DownloadConfig().speedLimit)

  /** The speed modes it runs, or `null`. */
  val speedModes: FakeSpeedModes? = settings?.let {
    FakeSpeedModes(it, clock = clock) { limit -> config = config.copy(speedLimit = limit) }
  }

  /** Refuses config and speed mode changes while set. */
  var rejects: Boolean = false
    set(value) {
      field = value
      speedModes?.failure = if (value) IllegalStateException("Engine stopped") else null
    }

  init {
    speedModes?.let { config = config.copy(speedLimit = it.states.value.limit) }
  }

  override fun <T> subscribe(interest: Interest<T>, interval: Duration): Flow<T> =
    speedModes?.follow(interest) ?: base.subscribe(interest, interval)

  @Suppress("UNCHECKED_CAST")
  override fun <T : Any> controller(key: ControlKey<T>): T? =
    speedModes.takeIf { key === SpeedModes.Key } as T?

  override suspend fun status(): KetchStatus = testStatus(
    name = backendLabel,
    config = config,
    features = if (speedModes == null) emptySet() else setOf(KetchFeatures.SPEED_MODES),
  )

  override suspend fun updateConfig(config: DownloadConfig) {
    if (rejects) throw IllegalStateException("Engine stopped")
    val limit = speedModes?.configure(config.speedLimit) ?: config.speedLimit
    this.config = config.copy(speedLimit = limit)
  }
}
