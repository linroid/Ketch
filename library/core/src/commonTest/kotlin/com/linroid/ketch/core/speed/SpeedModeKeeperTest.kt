package com.linroid.ketch.core.speed

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SpeedLimitMode
import com.linroid.ketch.api.SpeedModeChange
import com.linroid.ketch.api.SpeedRule
import com.linroid.ketch.api.SpeedScheduler
import com.linroid.ketch.api.SpeedSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedModeKeeperTest {

  private val start = Instant.parse("2026-09-30T09:59:00Z")

  /** The instance's limit as the keeper applied it, with what it saved. */
  private class Instance {
    val applied = mutableListOf<SpeedLimit>()
    val saved = mutableListOf<SpeedSettings>()
    val peaks = mutableListOf<ObservedPeak>()
  }

  private fun TestScope.keeper(
    instance: Instance,
    settings: SpeedSettings = SpeedSettings(),
    cap: SpeedLimit = SpeedLimit.Unlimited,
    observedPeak: ObservedPeak = ObservedPeak(),
    speed: Flow<Long> = emptyFlow(),
  ): SpeedModeKeeper {
    val clock = object : Clock {
      override fun now(): Instant = start + testScheduler.currentTime.milliseconds
    }
    val options = SpeedModeOptions(
      settings = settings,
      observedPeak = observedPeak,
      saveSettings = { instance.saved += it },
      savePeak = { instance.peaks += it },
    )
    return SpeedModeKeeper(
      options = options,
      cap = cap,
      apply = { instance.applied += it },
      scope = backgroundScope,
      speed = speed,
      scheduler = SpeedScheduler { TimeZone.UTC },
      clock = clock,
    )
  }

  @Test
  fun update_slowLaneWithoutHistory_appliesOneMegabyte() = runTest {
    val instance = Instance()
    val keeper = keeper(instance)

    val state = keeper.update(SpeedModeChange(mode = SpeedLimitMode.SlowLane))

    assertEquals(listOf(SpeedLimit.mbps(1)), instance.applied)
    assertTrue(state.inSlowLane)
    assertEquals(SpeedLimit.mbps(1), state.limit)
  }

  @Test
  fun update_slowLaneWithPeak_appliesSuggestion() = runTest {
    val instance = Instance()
    val keeper = keeper(instance, observedPeak = ObservedPeak(10_000_000, 0))

    keeper.update(SpeedModeChange(mode = SpeedLimitMode.SlowLane))

    assertEquals(listOf(SpeedLimit.of(3_000_000)), instance.applied)
  }

  @Test
  fun suggestSlowLane_peak_isThirtyPercentButAtLeastTheMinimum() {
    val cases = listOf(
      10_000_000L to SpeedLimit.of(3_000_000),
      SpeedLimit.kbps(500).bytesPerSecond to SpeedSettings.MIN_SLOW_LANE,
      // Without a peak it is 1 MB/s.
      0L to SpeedLimit.mbps(1),
    )
    for ((peak, expected) in cases) {
      assertEquals(expected, SpeedSettings.suggestSlowLane(peak), "suggestSlowLane($peak)")
    }
  }

  @Test
  fun update_slowLaneBelowMinimum_raisedToMinimum() = runTest {
    val instance = Instance()
    val keeper = keeper(instance, settings = SpeedSettings(mode = SpeedLimitMode.SlowLane))

    val state = keeper.update(SpeedModeChange(slowLane = SpeedLimit.kbps(64)))

    assertEquals(SpeedSettings.MIN_SLOW_LANE, state.settings.slowLane)
    assertEquals(SpeedSettings.MIN_SLOW_LANE, instance.applied.last())
  }

  @Test
  fun update_slowLaneUnlimited_followsSuggestion() = runTest {
    val instance = Instance()
    val settings = SpeedSettings(slowLane = SpeedLimit.mbps(2))
    val keeper = keeper(instance, settings, observedPeak = ObservedPeak(10_000_000, 0))

    val state = keeper.update(SpeedModeChange(slowLane = SpeedLimit.Unlimited))

    assertNull(state.settings.slowLane)
    assertEquals(SpeedLimit.of(3_000_000), state.slowLaneSpeed)
  }

  @Test
  fun init_savedSlowLane_startsWithSlowLaneLimit() = runTest {
    val settings = SpeedSettings(mode = SpeedLimitMode.SlowLane, slowLane = SpeedLimit.mbps(2))

    val keeper = keeper(Instance(), settings, cap = SpeedLimit.mbps(5))

    assertEquals(SpeedLimit.mbps(2), keeper.limit)
    assertEquals(SpeedLimit.mbps(5), keeper.state.value.settings.standard)
  }

  @Test
  fun init_savedStandingCap_replacedByInstanceLimit() = runTest {
    val settings = SpeedSettings(standard = SpeedLimit.mbps(5))

    val keeper = keeper(Instance(), settings, cap = SpeedLimit.mbps(8))

    assertEquals(SpeedLimit.mbps(8), keeper.limit)
    assertEquals(SpeedLimit.mbps(8), keeper.state.value.settings.standard)
  }

  @Test
  fun update_slowLaneFasterThanStandingCap_keepsCap() = runTest {
    val instance = Instance()
    val settings = SpeedSettings(slowLane = SpeedLimit.mbps(2))
    val keeper = keeper(instance, settings, cap = SpeedLimit.kbps(512))

    val state = keeper.update(SpeedModeChange(mode = SpeedLimitMode.SlowLane))

    assertEquals(emptyList(), instance.applied)
    assertEquals(SpeedLimit.kbps(512), state.limit)
  }

  @Test
  fun update_slowLaneThenFull_restoresStandingCap() = runTest {
    val instance = Instance()
    val keeper = keeper(instance, cap = SpeedLimit.mbps(5))

    keeper.update(SpeedModeChange(mode = SpeedLimitMode.SlowLane))
    val state = keeper.update(SpeedModeChange(mode = SpeedLimitMode.Full))

    assertEquals(listOf(SpeedLimit.mbps(1), SpeedLimit.mbps(5)), instance.applied)
    assertFalse(state.inSlowLane)
  }

  @Test
  fun update_standardInSlowLane_keepsSlowLane() = runTest {
    val instance = Instance()
    val keeper = keeper(instance)
    keeper.update(SpeedModeChange(mode = SpeedLimitMode.SlowLane))

    val state = keeper.update(SpeedModeChange(standard = SpeedLimit.mbps(20)))

    assertEquals(listOf(SpeedLimit.mbps(1)), instance.applied)
    assertEquals(SpeedLimit.mbps(20), state.settings.standard)
  }

  @Test
  fun update_slowLaneSpeedAndModeAtOnce_appliesOnce() = runTest {
    val instance = Instance()
    val keeper = keeper(instance)

    keeper.update(SpeedModeChange(mode = SpeedLimitMode.SlowLane, slowLane = SpeedLimit.mbps(3)))

    assertEquals(listOf(SpeedLimit.mbps(3)), instance.applied)
  }

  @Test
  fun update_moreRulesThanMax_keepsFirstThree() = runTest {
    val keeper = keeper(Instance())
    val rules = List(5) { SpeedRule(start = "0$it:00", end = "0$it:30") }

    val state = keeper.update(SpeedModeChange(rules = rules))

    assertEquals(rules.take(SpeedSettings.MAX_RULES), state.settings.rules)
  }

  @Test
  fun update_change_savesSettings() = runTest {
    val instance = Instance()
    val keeper = keeper(instance, cap = SpeedLimit.mbps(5))

    keeper.update(SpeedModeChange(mode = SpeedLimitMode.SlowLane))
    keeper.update(SpeedModeChange(mode = SpeedLimitMode.SlowLane))

    val expected = SpeedSettings(mode = SpeedLimitMode.SlowLane, standard = SpeedLimit.mbps(5))
    assertEquals(listOf(expected), instance.saved)
  }

  @Test
  fun configure_fullSpeedNewLimit_becomesStandingCap() = runTest {
    val instance = Instance()
    val keeper = keeper(instance)
    val configured = mutableListOf<SpeedLimit>()

    keeper.configure(SpeedLimit.mbps(8)) { configured += it }

    assertEquals(listOf(SpeedLimit.mbps(8)), configured)
    assertEquals(SpeedLimit.mbps(8), keeper.state.value.settings.standard)
    assertEquals(SpeedLimit.mbps(8), instance.saved.single().standard)
  }

  @Test
  fun configure_slowLaneNewLimit_setsCapAndKeepsSlowLane() = runTest {
    val keeper = keeper(Instance(), SpeedSettings(mode = SpeedLimitMode.SlowLane))
    val configured = mutableListOf<SpeedLimit>()

    keeper.configure(SpeedLimit.mbps(8)) { configured += it }

    assertEquals(listOf(SpeedLimit.mbps(1)), configured)
    assertEquals(SpeedLimit.mbps(8), keeper.state.value.settings.standard)
  }

  @Test
  fun configure_slowLaneLimitInEffect_changesNothing() = runTest {
    val instance = Instance()
    val keeper = keeper(instance, SpeedSettings(mode = SpeedLimitMode.SlowLane))
    val configured = mutableListOf<SpeedLimit>()

    // A client writing back the config it read, such as with another connection count.
    keeper.configure(SpeedLimit.mbps(1)) { configured += it }

    assertEquals(listOf(SpeedLimit.mbps(1)), configured)
    assertEquals(SpeedLimit.Unlimited, keeper.state.value.settings.standard)
    assertEquals(emptyList(), instance.saved)
  }

  @Test
  fun auto_ruleStartsAndEnds_appliesSlowLaneThenStandingCap() = runTest {
    val instance = Instance()
    val rule = SpeedRule(start = "10:00", end = "10:30")
    val keeper = keeper(instance, SpeedSettings(rules = listOf(rule)))

    val state = keeper.update(SpeedModeChange(mode = SpeedLimitMode.Auto))
    runCurrent()
    assertFalse(state.inSlowLane)
    assertEquals(Instant.parse("2026-09-30T10:00:00Z"), state.until)
    assertEquals(emptyList(), instance.applied)

    advanceTimeBy(1.minutes)
    runCurrent()
    assertTrue(keeper.state.value.inSlowLane)
    assertEquals(Instant.parse("2026-09-30T10:30:00Z"), keeper.state.value.until)
    assertEquals(listOf(SpeedLimit.mbps(1)), instance.applied)

    advanceTimeBy(30.minutes)
    runCurrent()
    assertEquals(listOf(SpeedLimit.mbps(1), SpeedLimit.Unlimited), instance.applied)
    assertFalse(keeper.state.value.inSlowLane)
  }

  @Test
  fun init_autoInsideRule_startsInSlowLane() = runTest {
    val rule = SpeedRule(start = "09:00", end = "18:00")
    val settings = SpeedSettings(mode = SpeedLimitMode.Auto, rules = listOf(rule))

    val keeper = keeper(Instance(), settings)

    assertTrue(keeper.state.value.inSlowLane)
    assertEquals(SpeedLimit.mbps(1), keeper.limit)
  }

  @Test
  fun update_fullDuringAutoRule_appliesStandingCap() = runTest {
    val instance = Instance()
    val rule = SpeedRule(start = "09:00", end = "18:00")
    val settings = SpeedSettings(mode = SpeedLimitMode.Auto, rules = listOf(rule))
    val keeper = keeper(instance, settings)
    runCurrent()

    keeper.update(SpeedModeChange(mode = SpeedLimitMode.Full))
    advanceTimeBy(2.minutes)
    runCurrent()

    assertEquals(listOf(SpeedLimit.Unlimited), instance.applied)
  }

  @Test
  fun observedPeak_fasterSpeed_raisesPeakAndSuggestion() = runTest {
    val speed = MutableStateFlow(0L)
    val keeper = keeper(Instance(), observedPeak = ObservedPeak(1_000, 0), speed = speed)

    speed.value = 10_000_000
    runCurrent()

    assertEquals(10_000_000, keeper.state.value.observedPeak)
    assertEquals(SpeedLimit.of(3_000_000), keeper.state.value.suggestedSlowLane)
  }

  @Test
  fun observedPeak_slowerSpeedWithinWeek_keepsPeak() = runTest {
    val speed = MutableStateFlow(0L)
    val peak = ObservedPeak(10_000, (start - 1.days).toEpochMilliseconds())
    val keeper = keeper(Instance(), observedPeak = peak, speed = speed)

    speed.value = 5_000
    runCurrent()

    assertEquals(10_000, keeper.state.value.observedPeak)
  }

  @Test
  fun observedPeak_peakOlderThanWeek_replacedBySlowerSpeed() = runTest {
    val speed = MutableStateFlow(0L)
    val peak = ObservedPeak(10_000, (start - 8.days).toEpochMilliseconds())
    val keeper = keeper(Instance(), observedPeak = peak, speed = speed)

    speed.value = 5_000
    runCurrent()

    assertEquals(5_000, keeper.state.value.observedPeak)
  }

  @Test
  fun observedPeak_inSlowLane_ignoresSpeed() = runTest {
    val speed = MutableStateFlow(0L)
    val settings = SpeedSettings(mode = SpeedLimitMode.SlowLane)
    val keeper = keeper(Instance(), settings, speed = speed)

    speed.value = 5_000
    runCurrent()

    assertEquals(0, keeper.state.value.observedPeak)
  }

  @Test
  fun observedPeak_raised_savedOnceItHolds() = runTest {
    val instance = Instance()
    val speed = MutableStateFlow(0L)
    keeper(instance, speed = speed)

    speed.value = 5_000
    runCurrent()
    speed.value = 6_000
    runCurrent()
    advanceTimeBy(5.seconds)
    assertEquals(emptyList(), instance.peaks)
    advanceTimeBy(6.seconds)

    assertEquals(listOf(ObservedPeak(6_000, start.toEpochMilliseconds())), instance.peaks)
  }
}
