package com.linroid.ketch.app.state

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SpeedLimitMode
import com.linroid.ketch.api.SpeedModeChange
import com.linroid.ketch.api.SpeedModeState
import com.linroid.ketch.api.SpeedModes
import com.linroid.ketch.api.SpeedRule
import com.linroid.ketch.api.SpeedSettings
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlin.time.Instant

/** What caps a device's total download speed. */
sealed interface SpeedMode {
  /** Downloads run at the device's standing cap, which is unlimited unless one is set. */
  data object Full : SpeedMode

  /** Downloads run at the slow lane speed until switched back. */
  data object SlowLane : SpeedMode

  /**
   * Speed rules turn the slow lane on and off.
   *
   * @property slowLane whether a rule has the slow lane on now.
   * @property until when that changes next; `null` when no rule changes it.
   */
  data class Auto(
    val slowLane: Boolean = false,
    val until: Instant? = null,
  ) : SpeedMode
}

/** Whether the slow lane is in effect, switched on by hand or by a rule. */
val SpeedMode.isSlowLane: Boolean
  get() = this is SpeedMode.SlowLane || (this is SpeedMode.Auto && slowLane)

/** The mode [this] state has in effect. */
val SpeedModeState.mode: SpeedMode
  get() = when (settings.mode) {
    SpeedLimitMode.Full -> SpeedMode.Full
    SpeedLimitMode.SlowLane -> SpeedMode.SlowLane
    SpeedLimitMode.Auto -> SpeedMode.Auto(slowLane = inSlowLane, until = until)
  }

/**
 * Switches one device between full speed, the slow lane and Auto speed rules, which the device
 * runs itself ([SpeedModes]), so they hold while the app is closed or away.
 *
 * It shows the device's speed mode as the device last reported it through [states], and as each
 * change it makes returns it; until the first report, [state] is `null` and the rest shows full
 * speed.
 *
 * @param modes the device's speed modes.
 * @param states what the device reports about them, such as
 *   `api.subscribe(Interest.SpeedMode)`; collected in [scope].
 * @param scope follows [states].
 * @param deviceId device whose speed this controls; used in log messages.
 */
class SpeedModeController(
  private val modes: SpeedModes,
  states: Flow<SpeedModeState>,
  scope: CoroutineScope,
  val deviceId: String = LOCAL_DEVICE_ID,
) {
  private val log = KetchLogger("SpeedMode")
  private val _state = MutableStateFlow<SpeedModeState?>(null)
  private val _settings = MutableStateFlow(SpeedSettings())
  private val _mode = MutableStateFlow<SpeedMode>(SpeedMode.Full)

  /** What the device last reported; `null` until it has. */
  val state: StateFlow<SpeedModeState?> = _state.asStateFlow()

  /** Speed settings of the device; the standing cap is [SpeedSettings.standard]. */
  val settings: StateFlow<SpeedSettings> = _settings.asStateFlow()

  /** Mode in effect; for Auto, whether a rule has the slow lane on and until when. */
  val mode: StateFlow<SpeedMode> = _mode.asStateFlow()

  /** Slow lane speed suggested from the device's observed peak. */
  val suggestedSlowLane: SpeedLimit
    get() = _state.value?.suggestedSlowLane ?: SpeedSettings.DEFAULT_SLOW_LANE

  /** Speed of the slow lane: the one set, or [suggestedSlowLane]. */
  val slowLaneSpeed: SpeedLimit
    get() = _state.value?.slowLaneSpeed ?: _settings.value.slowLane ?: suggestedSlowLane

  init {
    scope.launch { states.collect(::accept) }
  }

  /**
   * Switches to [mode] and applies it.
   *
   * @throws Exception when the device rejects the change; the mode is then unchanged.
   */
  suspend fun setMode(mode: SpeedLimitMode) {
    update(SpeedModeChange(mode = mode))
  }

  /**
   * Turns the slow lane off when it is in effect, by hand or by a rule, and on otherwise.
   *
   * @return the mode switched to.
   * @throws Exception when the device rejects the change; the mode is then unchanged.
   */
  suspend fun toggleSlowLane(): SpeedLimitMode {
    val next = if (_mode.value.isSlowLane) SpeedLimitMode.Full else SpeedLimitMode.SlowLane
    setMode(next)
    return next
  }

  /**
   * Sets the standing cap of full speed, [SpeedLimit.Unlimited] for none.
   *
   * @throws Exception when the device rejects the change; the cap is then unchanged.
   */
  suspend fun setStandard(limit: SpeedLimit) {
    update(SpeedModeChange(standard = limit))
  }

  /**
   * Sets the slow lane speed; `null` or [SpeedLimit.Unlimited] uses [suggestedSlowLane]. Speeds
   * below [SpeedSettings.MIN_SLOW_LANE] are raised to it.
   *
   * @throws Exception when the device rejects the change; the speed is then unchanged.
   */
  suspend fun setSlowLane(limit: SpeedLimit?) {
    update(SpeedModeChange(slowLane = limit ?: SpeedLimit.Unlimited))
  }

  /**
   * Replaces the Auto rules; only the first [SpeedSettings.MAX_RULES] are kept.
   *
   * @throws Exception when the device rejects the change; the rules are then unchanged.
   */
  suspend fun setRules(rules: List<SpeedRule>) {
    update(SpeedModeChange(rules = rules))
  }

  /**
   * Applies every field [change] sets at once, such as a slow lane speed and the slow lane.
   *
   * @throws Exception when the device rejects the change; nothing changes then.
   */
  suspend fun update(change: SpeedModeChange) {
    val result = try {
      modes.update(change)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      log.w { "Could not change the speed mode on deviceId=$deviceId: ${e.describeCauses()}" }
      throw e
    }
    accept(result)
  }

  private fun accept(state: SpeedModeState) {
    _state.value = state
    _settings.value = state.settings
    _mode.value = state.mode
  }
}
