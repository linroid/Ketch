package com.linroid.ketch.app

import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SpeedLimitMode
import com.linroid.ketch.api.SpeedModeChange
import com.linroid.ketch.api.SpeedModeState
import com.linroid.ketch.api.SpeedModes
import com.linroid.ketch.api.SpeedScheduler
import com.linroid.ketch.api.SpeedSettings
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.datetime.TimeZone
import kotlin.time.Clock

/**
 * The speed modes a test device runs, roughly as an engine does: every change applies at once,
 * Auto rules are read in UTC at [clock]'s time, and the limit that results goes to [onLimit],
 * such as the device's download config.
 *
 * @param observedPeak the peak the slow lane's suggested speed derives from.
 */
class FakeSpeedModes(
  settings: SpeedSettings = SpeedSettings(),
  private val observedPeak: Long = 0,
  private val clock: Clock = Clock.System,
  private val onLimit: (SpeedLimit) -> Unit = {},
) : SpeedModes {
  private val scheduler = SpeedScheduler { TimeZone.UTC }

  /** Fails every change while set. */
  var failure: Exception? = null

  /** What the device reports, as [Interest.SpeedMode] follows it. */
  val states = MutableStateFlow(stateOf(settings))

  /** Changes the device received, oldest first. */
  val changes = mutableListOf<SpeedModeChange>()

  override suspend fun state(): SpeedModeState = states.value

  override suspend fun update(change: SpeedModeChange): SpeedModeState {
    failure?.let { throw it }
    changes += change
    val current = states.value.settings
    return publish(
      current.copy(
        mode = change.mode ?: current.mode,
        standard = change.standard ?: current.standard,
        slowLane = when (val speed = change.slowLane) {
          null -> current.slowLane
          else -> speed.takeUnless { it.isUnlimited }
        },
        rules = change.rules ?: current.rules,
      ),
    )
  }

  /**
   * What the device's engine makes of a new download config's [requested] limit: one other than
   * the limit in effect is the new standing cap. Returns the limit in effect then.
   */
  fun configure(requested: SpeedLimit): SpeedLimit {
    val current = states.value
    val next = if (requested != current.limit) {
      current.settings.copy(standard = requested)
    } else {
      current.settings
    }
    return publish(next).limit
  }

  /** [states] for [Interest.SpeedMode]; `null` for other interests. */
  @Suppress("UNCHECKED_CAST")
  fun <T> follow(interest: Interest<T>): Flow<T>? =
    (states as Flow<T>).takeIf { interest == Interest.SpeedMode }

  private fun publish(settings: SpeedSettings): SpeedModeState {
    val state = stateOf(settings)
    states.value = state
    onLimit(state.limit)
    return state
  }

  private fun stateOf(settings: SpeedSettings): SpeedModeState {
    val window = scheduler.evaluate(settings.rules, clock.now())
      .takeIf { settings.mode == SpeedLimitMode.Auto }
    val inSlowLane = settings.mode == SpeedLimitMode.SlowLane || window?.slowLane == true
    val suggested = SpeedSettings.suggestSlowLane(observedPeak)
    val slowLane = settings.slowLane ?: suggested
    val limit = when {
      !inSlowLane -> settings.standard
      settings.standard.isUnlimited -> slowLane
      else -> SpeedLimit.of(minOf(slowLane.bytesPerSecond, settings.standard.bytesPerSecond))
    }
    return SpeedModeState(
      settings = settings,
      inSlowLane = inSlowLane,
      until = window?.until,
      limit = limit,
      slowLaneSpeed = slowLane,
      suggestedSlowLane = suggested,
      observedPeak = observedPeak,
    )
  }
}
