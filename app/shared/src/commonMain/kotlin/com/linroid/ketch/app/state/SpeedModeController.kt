package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.config.SpeedLimitMode
import com.linroid.ketch.config.SpeedRule
import com.linroid.ketch.config.SpeedSettings
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
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

/**
 * Highest total download speed seen recently, saved as `UiPreferences.observedPeak` and
 * `observedPeakAt`.
 *
 * @property bytesPerSecond the speed; `0` until there is history.
 * @property atEpochMillis when it was seen, in epoch milliseconds.
 */
data class ObservedPeak(
  val bytesPerSecond: Long = 0,
  val atEpochMillis: Long = 0,
)

/**
 * Switches one device between full speed, the slow lane and Auto speed rules.
 *
 * A change applies at once: the controller replaces the speed limit of the device's current
 * [DownloadConfig] and hands the result to [apply]. Outside full speed, including a slow lane
 * or Auto mode restored from [settings], the controller keeps the device at the limit of the
 * mode: it checks again whenever an Auto rule starts or ends, and at least every minute, so a
 * device that comes back online or lost its limit gets it again. Leaving full speed remembers
 * the device's current limit as the standing cap, so switching back restores it.
 *
 * The owner saves [settings] and [observedPeak] when they change.
 *
 * @param config reads the device's current download config.
 * @param apply applies a download config to the device.
 * @param scope keeps the limit of the mode applied and records the observed peak.
 * @param deviceId device whose speed this controls; used in log messages.
 * @param settings settings to start from, such as the saved `[speed]` section.
 * @param observedPeak observed peak to start from, such as the one saved under `[ui]`.
 * @param speed the device's total download speed in bytes per second, which raises
 *   [observedPeak].
 * @param scheduler evaluates the Auto rules.
 * @param clock source of the current time.
 */
class SpeedModeController(
  private val config: suspend () -> DownloadConfig,
  private val apply: suspend (DownloadConfig) -> Unit,
  scope: CoroutineScope,
  val deviceId: String = LOCAL_DEVICE_ID,
  settings: SpeedSettings = SpeedSettings(),
  observedPeak: ObservedPeak = ObservedPeak(),
  speed: Flow<Long> = emptyFlow(),
  private val scheduler: SpeedScheduler = SpeedScheduler(),
  private val clock: Clock = Clock.System,
) {
  private val log = KetchLogger("SpeedMode")
  private val lock = Mutex()
  private val _settings = MutableStateFlow(normalize(settings))
  private val _observedPeak = MutableStateFlow(observedPeak)
  private var window: SpeedWindow? = null
  private val _mode = MutableStateFlow<SpeedMode>(SpeedMode.Full)

  /** Speed settings of the device, with the slow lane and rules kept within their limits. */
  val settings: StateFlow<SpeedSettings> = _settings.asStateFlow()

  /** Mode in effect; for Auto, whether a rule has the slow lane on and until when. */
  val mode: StateFlow<SpeedMode> = _mode.asStateFlow()

  /** Highest total speed seen in the last week, outside the slow lane. */
  val observedPeak: StateFlow<ObservedPeak> = _observedPeak.asStateFlow()

  /** Slow lane speed suggested from [observedPeak]. */
  val suggestedSlowLane: SpeedLimit
    get() = suggestSlowLane(_observedPeak.value.bytesPerSecond)

  /** Speed of the slow lane: the one set, or [suggestedSlowLane]. */
  val slowLaneSpeed: SpeedLimit
    get() = _settings.value.slowLane ?: suggestedSlowLane

  init {
    refreshMode(_settings.value)
    scope.launch { speed.collect(::recordSpeed) }
    scope.launch { keepLimit() }
  }

  /**
   * Switches to [mode] and applies it.
   *
   * @throws Exception when the device rejects the new limit; the mode is then unchanged.
   */
  suspend fun setMode(mode: SpeedLimitMode) {
    update { it.copy(mode = mode) }
  }

  /**
   * Turns the slow lane off when it is in effect, by hand or by a rule, and on otherwise.
   *
   * @return the mode switched to.
   * @throws Exception when the device rejects the new limit; the mode is then unchanged.
   */
  suspend fun toggleSlowLane(): SpeedLimitMode {
    val next = if (_mode.value.isSlowLane) SpeedLimitMode.Full else SpeedLimitMode.SlowLane
    setMode(next)
    return next
  }

  /**
   * Sets the standing cap of full speed, [SpeedLimit.Unlimited] for none.
   *
   * @throws Exception when the device rejects the new limit; the cap is then unchanged.
   */
  suspend fun setStandard(limit: SpeedLimit) {
    update { it.copy(standard = limit) }
  }

  /**
   * Sets the slow lane speed; `null` or [SpeedLimit.Unlimited] uses [suggestedSlowLane]. Speeds
   * below [MIN_SLOW_LANE] are raised to it.
   *
   * @throws Exception when the device rejects the new limit; the speed is then unchanged.
   */
  suspend fun setSlowLane(limit: SpeedLimit?) {
    update { it.copy(slowLane = limit) }
  }

  /**
   * Replaces the Auto rules; only the first [SpeedSettings.MAX_RULES] are kept.
   *
   * @throws Exception when the device rejects the new limit; the rules are then unchanged.
   */
  suspend fun setRules(rules: List<SpeedRule>) {
    update { it.copy(rules = rules) }
  }

  private suspend fun update(transform: (SpeedSettings) -> SpeedSettings) {
    lock.withLock {
      val previous = _settings.value
      var next = normalize(transform(previous))
      if (next == previous) return
      val current = config()
      if (previous.mode == SpeedLimitMode.Full && next.mode != SpeedLimitMode.Full) {
        next = next.copy(standard = current.speedLimit)
      }
      _settings.value = next
      try {
        refreshMode(next)
        applyLimit(next, current)
      } catch (e: Throwable) {
        _settings.value = previous
        refreshMode(previous)
        if (e !is CancellationException) {
          log.w { "Could not apply speed mode on deviceId=$deviceId: ${e.describeCauses()}" }
        }
        throw e
      }
    }
  }

  // Outside full speed, keeps the limit of the mode applied: evaluates the rules, applies the
  // result and sleeps until the next rule starts or ends. Sleeps are capped so a clock change, a
  // suspended machine or a device that lost its limit is noticed.
  private suspend fun keepLimit() {
    _settings.map { if (it.mode == SpeedLimitMode.Full) null else it.mode to it.rules }
      .distinctUntilChanged()
      .collectLatest { key ->
        if (key == null) return@collectLatest
        var failing = false
        while (true) {
          val evaluated = lock.withLock {
            val settings = _settings.value
            // A change that the device rejected can have restored full speed meanwhile.
            if (settings.mode == SpeedLimitMode.Full) return@collectLatest
            refreshMode(settings)
            try {
              applyLimit(settings, config())
              failing = false
            } catch (e: CancellationException) {
              throw e
            } catch (e: Exception) {
              // An offline device fails every minute; only the first failure is a warning.
              val message = "Could not apply the speed mode on deviceId=$deviceId"
              if (failing) {
                log.d { "$message: ${e.describeCauses()}" }
              } else {
                log.w { "$message: ${e.describeCauses()}" }
              }
              failing = true
            }
            window
          }
          val until = evaluated?.until
          val wait = if (until == null) MAX_RULE_WAIT else until - clock.now()
          delay(wait.coerceIn(MIN_RULE_WAIT, MAX_RULE_WAIT))
        }
      }
  }

  private suspend fun applyLimit(settings: SpeedSettings, current: DownloadConfig) {
    val limit = limitFor(settings, window)
    if (current.speedLimit == limit) return
    log.i { "Speed limit on deviceId=$deviceId set to $limit, mode ${_mode.value}" }
    apply(current.copy(speedLimit = limit))
  }

  // Evaluates the Auto rules of settings and publishes the mode they give.
  private fun refreshMode(settings: SpeedSettings) {
    window = if (settings.mode == SpeedLimitMode.Auto) {
      scheduler.evaluate(settings.rules, clock.now())
    } else {
      null
    }
    _mode.value = when (settings.mode) {
      SpeedLimitMode.Full -> SpeedMode.Full
      SpeedLimitMode.SlowLane -> SpeedMode.SlowLane
      SpeedLimitMode.Auto -> SpeedMode.Auto(
        slowLane = window?.slowLane == true,
        until = window?.until,
      )
    }
  }

  private fun limitFor(settings: SpeedSettings, window: SpeedWindow?): SpeedLimit {
    val slowLane = when (settings.mode) {
      SpeedLimitMode.Full -> false
      SpeedLimitMode.SlowLane -> true
      SpeedLimitMode.Auto -> window?.slowLane == true
    }
    if (!slowLane) return settings.standard
    // The slow lane never runs faster than the standing cap.
    val speed = settings.slowLane ?: suggestedSlowLane
    return if (settings.standard.isUnlimited ||
      speed.bytesPerSecond < settings.standard.bytesPerSecond
    ) {
      speed
    } else {
      settings.standard
    }
  }

  // The peak is the highest speed of the last week: a newer, lower speed replaces it once it
  // is a week old. Speeds held down by the slow lane are ignored.
  private fun recordSpeed(bytesPerSecond: Long) {
    if (bytesPerSecond <= 0 || _mode.value.isSlowLane) return
    val now = clock.now().toEpochMilliseconds()
    _observedPeak.update { peak ->
      val expired = now - peak.atEpochMillis > PEAK_WINDOW.inWholeMilliseconds
      if (bytesPerSecond > peak.bytesPerSecond || expired) {
        ObservedPeak(bytesPerSecond, now)
      } else {
        peak
      }
    }
  }

  companion object {
    /** Slowest the slow lane goes, 256 KB/s. */
    val MIN_SLOW_LANE: SpeedLimit = SpeedLimit.kbps(256)

    /** Slow lane speed until there is a peak to derive one from, 1 MB/s. */
    val DEFAULT_SLOW_LANE: SpeedLimit = SpeedLimit.mbps(1)

    /** Share of the observed peak the slow lane is suggested at. */
    const val SLOW_LANE_PERCENT: Int = 30

    private val PEAK_WINDOW = 7.days
    private val MIN_RULE_WAIT = Duration.ZERO
    private val MAX_RULE_WAIT = 1.minutes

    /**
     * Suggests a slow lane speed: [SLOW_LANE_PERCENT] of [peakBytesPerSecond], at least
     * [MIN_SLOW_LANE], or [DEFAULT_SLOW_LANE] without a peak.
     */
    fun suggestSlowLane(peakBytesPerSecond: Long): SpeedLimit {
      if (peakBytesPerSecond <= 0) return DEFAULT_SLOW_LANE
      val speed = peakBytesPerSecond * SLOW_LANE_PERCENT / 100
      return SpeedLimit.of(maxOf(speed, MIN_SLOW_LANE.bytesPerSecond))
    }

    private fun normalize(settings: SpeedSettings): SpeedSettings {
      val slowLane = settings.slowLane
        ?.takeUnless { it.isUnlimited }
        ?.let { SpeedLimit.of(maxOf(it.bytesPerSecond, MIN_SLOW_LANE.bytesPerSecond)) }
      return settings.copy(
        slowLane = slowLane,
        rules = settings.rules.take(SpeedSettings.MAX_RULES),
      )
    }
  }
}
