package com.linroid.ketch.core.speed

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SpeedLimitMode
import com.linroid.ketch.api.SpeedModeChange
import com.linroid.ketch.api.SpeedModeState
import com.linroid.ketch.api.SpeedModes
import com.linroid.ketch.api.SpeedScheduler
import com.linroid.ketch.api.SpeedSettings
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * Runs an instance's [SpeedModes]: holds its global speed limit at the standing cap, or at the
 * slow lane's speed while the slow lane is on, by hand or by an Auto rule. In Auto it evaluates
 * the rules again whenever one starts or ends, and at least every minute, so a clock change or a
 * suspended machine is noticed.
 *
 * Every limit the instance applies goes through it: its own through [apply], and those of
 * [com.linroid.ketch.api.KetchApi.updateConfig] through [configure], so the standing cap is
 * always current ([SpeedSettings.standard]).
 *
 * @param options settings and peak to start from, and where they are saved.
 * @param cap the instance's limit at start, which is the standing cap.
 * @param apply applies a global speed limit to the instance.
 * @param scope runs the rules, records [speed] and saves the peak.
 * @param speed the instance's total download speed in bytes per second, which raises the
 *   observed peak.
 */
internal class SpeedModeKeeper(
  options: SpeedModeOptions,
  cap: SpeedLimit,
  private val apply: suspend (SpeedLimit) -> Unit,
  scope: CoroutineScope,
  speed: Flow<Long>,
  private val scheduler: SpeedScheduler = SpeedScheduler(),
  private val clock: Clock = Clock.System,
) : SpeedModes {
  private val log = KetchLogger("SpeedModes")
  private val lock = Mutex()
  private val saveSettings = options.saveSettings
  private val savePeak = options.savePeak
  private val peak = MutableStateFlow(options.observedPeak)

  // Changed only under lock.
  private var settings = normalize(options.settings.copy(standard = cap))
  private val _state = MutableStateFlow(stateOf(settings, clock.now()))

  /** What the settings apply now; each change, also a rule starting or ending. */
  val state: StateFlow<SpeedModeState> = _state.asStateFlow()

  /** The limit the settings give at start, which the instance starts with. */
  val limit: SpeedLimit get() = _state.value.limit

  init {
    scope.launch { speed.collect(::recordSpeed) }
    scope.launch { followRules() }
    scope.launch { savePeaks() }
  }

  override suspend fun state(): SpeedModeState = _state.value

  override suspend fun update(change: SpeedModeChange): SpeedModeState = lock.withLock {
    val next = normalize(settings.with(change))
    if (next == settings) return@withLock _state.value
    if (next.mode != settings.mode) log.i { "Speed mode: ${next.mode}" }
    publish(next, stateOf(next, clock.now()))
  }

  /**
   * Applies [requested], the speed limit of a new download config, through [applyConfig]: one
   * other than the limit in effect is the new standing cap, which the mode then holds to; the
   * limit in effect itself, as a client that writes back the config it read sends it, changes
   * nothing.
   */
  suspend fun configure(requested: SpeedLimit, applyConfig: suspend (SpeedLimit) -> Unit) {
    lock.withLock {
      val current = _state.value
      val next = if (requested != current.limit) settings.copy(standard = requested) else settings
      val state = stateOf(next, clock.now())
      applyConfig(state.limit)
      if (state.limit != current.limit) logLimit(state)
      commit(next, state)
    }
  }

  // Applies state's limit if it changed and keeps next. Call under lock.
  private suspend fun publish(next: SpeedSettings, state: SpeedModeState): SpeedModeState {
    if (state.limit != _state.value.limit) {
      logLimit(state)
      apply(state.limit)
    }
    commit(next, state)
    return state
  }

  // Keeps next and state, and saves next if it changed. Call under lock, after the limit applied.
  private suspend fun commit(next: SpeedSettings, state: SpeedModeState) {
    val changed = next != settings
    settings = next
    _state.value = state
    if (!changed) return
    // The change applied, so it is saved even if its caller is cancelled meanwhile.
    withContext(NonCancellable) {
      try {
        saveSettings(next)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Could not save the speed settings: ${e.describeCauses()}" }
      }
    }
  }

  // In Auto, applies what the rules give, then sleeps until the next one starts or ends. Sleeps
  // are capped, so a clock change or a suspended machine is noticed.
  private suspend fun followRules() {
    _state.map { it.settings.mode to it.settings.rules }
      .distinctUntilChanged()
      .collectLatest { (mode, _) ->
        if (mode != SpeedLimitMode.Auto) return@collectLatest
        while (true) {
          val until = lock.withLock {
            if (settings.mode != SpeedLimitMode.Auto) return@collectLatest
            publish(settings, stateOf(settings, clock.now())).until
          }
          val wait = if (until == null) MAX_RULE_WAIT else until - clock.now()
          delay(wait.coerceIn(Duration.ZERO, MAX_RULE_WAIT))
        }
      }
  }

  // The peak is the highest speed of the last week: a newer, lower speed replaces it once it
  // is a week old. Speeds held down by the slow lane are ignored.
  private suspend fun recordSpeed(bytesPerSecond: Long) {
    if (bytesPerSecond <= 0 || _state.value.inSlowLane) return
    val now = clock.now()
    val millis = now.toEpochMilliseconds()
    val current = peak.value
    val expired = millis - current.atEpochMillis > PEAK_WINDOW.inWholeMilliseconds
    if (bytesPerSecond <= current.bytesPerSecond && !expired) return
    lock.withLock {
      peak.value = ObservedPeak(bytesPerSecond, millis)
      publish(settings, stateOf(settings, now))
    }
  }

  @OptIn(FlowPreview::class)
  private suspend fun savePeaks() {
    peak.drop(1).debounce(PEAK_SAVE_DELAY).collect { observed ->
      try {
        savePeak(observed)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Could not save the observed peak: ${e.describeCauses()}" }
      }
    }
  }

  private fun logLimit(state: SpeedModeState) {
    val lane = if (state.inSlowLane) "on" else "off"
    log.i { "Speed limit set to ${state.limit}: mode ${state.settings.mode}, slow lane $lane" }
  }

  private fun stateOf(settings: SpeedSettings, now: Instant): SpeedModeState {
    val window = if (settings.mode == SpeedLimitMode.Auto) {
      scheduler.evaluate(settings.rules, now)
    } else {
      null
    }
    val inSlowLane = settings.mode == SpeedLimitMode.SlowLane || window?.slowLane == true
    val observed = peak.value.bytesPerSecond
    val suggested = SpeedSettings.suggestSlowLane(observed)
    val slowLane = settings.slowLane ?: suggested
    val cap = settings.standard
    // The slow lane never runs faster than the standing cap.
    val limit = when {
      !inSlowLane -> cap
      cap.isUnlimited || slowLane.bytesPerSecond < cap.bytesPerSecond -> slowLane
      else -> cap
    }
    return SpeedModeState(
      settings = settings,
      inSlowLane = inSlowLane,
      until = window?.until,
      limit = limit,
      slowLaneSpeed = slowLane,
      suggestedSlowLane = suggested,
      observedPeak = observed,
    )
  }

  private companion object {
    val PEAK_WINDOW = 7.days
    val PEAK_SAVE_DELAY = 10.seconds
    val MAX_RULE_WAIT = 1.minutes

    fun SpeedSettings.with(change: SpeedModeChange): SpeedSettings = copy(
      mode = change.mode ?: mode,
      standard = change.standard ?: standard,
      slowLane = when (val speed = change.slowLane) {
        null -> slowLane
        else -> speed.takeUnless { it.isUnlimited }
      },
      rules = change.rules ?: rules,
    )

    fun normalize(settings: SpeedSettings): SpeedSettings {
      val minimum = SpeedSettings.MIN_SLOW_LANE.bytesPerSecond
      val slowLane = settings.slowLane
        ?.takeUnless { it.isUnlimited }
        ?.let { SpeedLimit.of(maxOf(it.bytesPerSecond, minimum)) }
      return settings.copy(
        slowLane = slowLane,
        rules = settings.rules.take(SpeedSettings.MAX_RULES),
      )
    }
  }
}
