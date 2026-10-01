package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.config.SpeedLimitMode
import com.linroid.ketch.config.SpeedRule
import com.linroid.ketch.config.SpeedSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
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
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedModeControllerTest {

  private val start = Instant.parse("2026-09-30T09:59:00Z")

  private class Device(var config: DownloadConfig = DownloadConfig()) {
    val applied = mutableListOf<SpeedLimit>()
    var failure: Exception? = null
    var latency = Duration.ZERO

    suspend fun apply(config: DownloadConfig) {
      delay(latency)
      failure?.let { throw it }
      applied += config.speedLimit
      this.config = config
    }
  }

  private fun TestScope.controller(
    device: Device,
    settings: SpeedSettings = SpeedSettings(),
    observedPeak: ObservedPeak = ObservedPeak(),
    speed: Flow<Long> = emptyFlow(),
  ): SpeedModeController {
    val clock = object : Clock {
      override fun now(): Instant = start + testScheduler.currentTime.milliseconds
    }
    return SpeedModeController(
      config = { device.config },
      apply = device::apply,
      scope = backgroundScope,
      settings = settings,
      observedPeak = observedPeak,
      speed = speed,
      scheduler = SpeedScheduler { TimeZone.UTC },
      clock = clock,
    )
  }

  @Test
  fun setMode_slowLaneWithoutHistory_appliesOneMegabyte() = runTest {
    val device = Device()
    val controller = controller(device)

    controller.setMode(SpeedLimitMode.SlowLane)

    assertEquals(listOf(SpeedLimit.mbps(1)), device.applied)
    assertEquals(SpeedMode.SlowLane, controller.mode.value)
  }

  @Test
  fun setMode_slowLaneWithPeak_appliesSuggestion() = runTest {
    val device = Device()
    val controller = controller(device, observedPeak = ObservedPeak(10_000_000, 0))

    controller.setMode(SpeedLimitMode.SlowLane)

    assertEquals(listOf(SpeedLimit.of(3_000_000)), device.applied)
  }

  @Test
  fun suggestSlowLane_peak_isThirtyPercent() {
    assertEquals(SpeedLimit.of(3_000_000), SpeedModeController.suggestSlowLane(10_000_000))
  }

  @Test
  fun suggestSlowLane_slowPeak_neverBelowMinimum() {
    assertEquals(
      SpeedModeController.MIN_SLOW_LANE,
      SpeedModeController.suggestSlowLane(SpeedLimit.kbps(500).bytesPerSecond)
    )
  }

  @Test
  fun suggestSlowLane_noPeak_isOneMegabyte() {
    assertEquals(SpeedLimit.mbps(1), SpeedModeController.suggestSlowLane(0))
  }

  @Test
  fun setSlowLane_belowMinimum_raisedToMinimum() = runTest {
    val device = Device()
    val controller = controller(device, settings = SpeedSettings(mode = SpeedLimitMode.SlowLane))
    runCurrent()

    controller.setSlowLane(SpeedLimit.kbps(64))

    assertEquals(SpeedModeController.MIN_SLOW_LANE, controller.settings.value.slowLane)
    assertEquals(SpeedModeController.MIN_SLOW_LANE, device.applied.last())
  }

  @Test
  fun init_savedSlowLaneMode_appliesSlowLane() = runTest {
    val device = Device()
    val settings = SpeedSettings(mode = SpeedLimitMode.SlowLane, slowLane = SpeedLimit.mbps(2))

    val controller = controller(device, settings = settings)
    runCurrent()

    assertEquals(listOf(SpeedLimit.mbps(2)), device.applied)
    assertEquals(SpeedMode.SlowLane, controller.mode.value)
  }

  @Test
  fun slowLane_deviceLosesLimit_appliedAgainWithinMinute() = runTest {
    val device = Device()
    val controller = controller(device)
    controller.setMode(SpeedLimitMode.SlowLane)
    runCurrent()

    device.config = DownloadConfig()
    advanceTimeBy(1.minutes)
    runCurrent()

    assertEquals(listOf(SpeedLimit.mbps(1), SpeedLimit.mbps(1)), device.applied)
  }

  @Test
  fun init_savedFullSpeed_keepsDeviceLimit() = runTest {
    val cap = SpeedLimit.mbps(5)
    val device = Device(DownloadConfig(speedLimit = cap))

    controller(device)
    advanceTimeBy(2.minutes)
    runCurrent()

    assertEquals(emptyList(), device.applied)
    assertEquals(cap, device.config.speedLimit)
  }

  @Test
  fun init_savedSlowLaneBelowMinimum_raisedToMinimum() = runTest {
    val settings = SpeedSettings(slowLane = SpeedLimit.kbps(100))

    val controller = controller(Device(), settings = settings)

    assertEquals(SpeedModeController.MIN_SLOW_LANE, controller.slowLaneSpeed)
  }

  @Test
  fun setSlowLane_unlimited_usesSuggestion() = runTest {
    val controller = controller(Device(), observedPeak = ObservedPeak(10_000_000, 0))

    controller.setSlowLane(SpeedLimit.Unlimited)

    assertEquals(null, controller.settings.value.slowLane)
    assertEquals(SpeedLimit.of(3_000_000), controller.slowLaneSpeed)
  }

  @Test
  fun setMode_slowLaneFasterThanStandingCap_keepsCap() = runTest {
    val cap = SpeedLimit.kbps(512)
    val device = Device(DownloadConfig(speedLimit = cap))
    val controller = controller(device, settings = SpeedSettings(slowLane = SpeedLimit.mbps(2)))

    controller.setMode(SpeedLimitMode.SlowLane)

    assertEquals(emptyList(), device.applied)
    assertEquals(cap, device.config.speedLimit)
  }

  @Test
  fun toggleSlowLane_twice_restoresStandingCap() = runTest {
    val cap = SpeedLimit.mbps(5)
    val device = Device(DownloadConfig(speedLimit = cap))
    val controller = controller(device)

    assertEquals(SpeedLimitMode.SlowLane, controller.toggleSlowLane())
    assertEquals(SpeedLimitMode.Full, controller.toggleSlowLane())

    assertEquals(listOf(SpeedLimit.mbps(1), cap), device.applied)
    assertEquals(cap, controller.settings.value.standard)
    assertEquals(SpeedMode.Full, controller.mode.value)
  }

  @Test
  fun setMode_applyFails_keepsPreviousMode() = runTest {
    val device = Device(DownloadConfig(speedLimit = SpeedLimit.mbps(5))).apply {
      failure = IllegalStateException("Connection lost")
      latency = 1.seconds
    }
    val controller = controller(device)

    assertFailsWith<IllegalStateException> { controller.setMode(SpeedLimitMode.SlowLane) }
    device.failure = null
    advanceTimeBy(2.minutes)
    runCurrent()

    assertEquals(SpeedLimitMode.Full, controller.settings.value.mode)
    assertEquals(SpeedMode.Full, controller.mode.value)
    assertEquals(emptyList(), device.applied)
  }

  @Test
  fun setStandard_fullSpeed_appliesCap() = runTest {
    val device = Device()
    val controller = controller(device)

    controller.setStandard(SpeedLimit.mbps(20))

    assertEquals(listOf(SpeedLimit.mbps(20)), device.applied)
  }

  @Test
  fun setSlowLane_fullSpeedWithACapSetElsewhere_keepsTheCap() = runTest {
    val device = Device()
    val controller = controller(device)
    device.config = device.config.copy(speedLimit = SpeedLimit.mbps(8))

    controller.setSlowLane(SpeedLimit.mbps(2))
    controller.setRules(listOf(SpeedRule()))
    controller.toggleSlowLane()
    controller.toggleSlowLane()

    assertEquals(listOf(SpeedLimit.mbps(2), SpeedLimit.mbps(8)), device.applied)
    assertEquals(SpeedLimit.mbps(8), controller.settings.value.standard)
  }

  @Test
  fun setStandard_slowLane_keepsSlowLane() = runTest {
    val device = Device()
    val controller = controller(device)
    controller.setMode(SpeedLimitMode.SlowLane)

    controller.setStandard(SpeedLimit.mbps(20))

    assertEquals(listOf(SpeedLimit.mbps(1)), device.applied)
  }

  @Test
  fun setRules_moreThanMax_keepsFirstThree() = runTest {
    val controller = controller(Device())
    val rules = List(5) { SpeedRule(start = "0$it:00", end = "0$it:30") }

    controller.setRules(rules)

    assertEquals(rules.take(SpeedSettings.MAX_RULES), controller.settings.value.rules)
  }

  @Test
  fun autoMode_ruleStartsAndEnds_appliesSlowLaneThenStandingCap() = runTest {
    val device = Device()
    val rule = SpeedRule(start = "10:00", end = "10:30")
    val controller = controller(device, settings = SpeedSettings(rules = listOf(rule)))

    controller.setMode(SpeedLimitMode.Auto)
    runCurrent()
    assertEquals(
      SpeedMode.Auto(slowLane = false, until = Instant.parse("2026-09-30T10:00:00Z")),
      controller.mode.value
    )
    assertEquals(emptyList(), device.applied)

    advanceTimeBy(1.minutes)
    runCurrent()
    assertEquals(
      SpeedMode.Auto(slowLane = true, until = Instant.parse("2026-09-30T10:30:00Z")),
      controller.mode.value
    )
    assertEquals(listOf(SpeedLimit.mbps(1)), device.applied)

    advanceTimeBy(30.minutes)
    runCurrent()
    assertEquals(listOf(SpeedLimit.mbps(1), SpeedLimit.Unlimited), device.applied)
    assertTrue(controller.mode.value is SpeedMode.Auto)
  }

  @Test
  fun init_autoModeInsideRule_appliesSlowLane() = runTest {
    val device = Device()
    val rule = SpeedRule(start = "09:00", end = "18:00")
    val settings = SpeedSettings(mode = SpeedLimitMode.Auto, rules = listOf(rule))

    val controller = controller(device, settings = settings)
    runCurrent()

    assertEquals(listOf(SpeedLimit.mbps(1)), device.applied)
    assertTrue(controller.mode.value.isSlowLane)
  }

  @Test
  fun toggleSlowLane_duringAutoRule_switchesToFullSpeed() = runTest {
    val device = Device()
    val rule = SpeedRule(start = "09:00", end = "18:00")
    val settings = SpeedSettings(mode = SpeedLimitMode.Auto, rules = listOf(rule))
    val controller = controller(device, settings = settings)
    runCurrent()

    assertEquals(SpeedLimitMode.Full, controller.toggleSlowLane())

    assertEquals(listOf(SpeedLimit.mbps(1), SpeedLimit.Unlimited), device.applied)
  }

  @Test
  fun observedPeak_fasterSpeed_raisesPeak() = runTest {
    val speed = MutableStateFlow(0L)
    val controller = controller(Device(), observedPeak = ObservedPeak(1_000, 0), speed = speed)

    speed.value = 5_000
    runCurrent()

    assertEquals(5_000, controller.observedPeak.value.bytesPerSecond)
    assertEquals(start.toEpochMilliseconds(), controller.observedPeak.value.atEpochMillis)
  }

  @Test
  fun observedPeak_slowerSpeedWithinWeek_keepsPeak() = runTest {
    val speed = MutableStateFlow(0L)
    val peak = ObservedPeak(10_000, (start - 1.days).toEpochMilliseconds())
    val controller = controller(Device(), observedPeak = peak, speed = speed)

    speed.value = 5_000
    runCurrent()

    assertEquals(peak, controller.observedPeak.value)
  }

  @Test
  fun observedPeak_peakOlderThanWeek_replacedBySlowerSpeed() = runTest {
    val speed = MutableStateFlow(0L)
    val peak = ObservedPeak(10_000, (start - 8.days).toEpochMilliseconds())
    val controller = controller(Device(), observedPeak = peak, speed = speed)

    speed.value = 5_000
    runCurrent()

    assertEquals(5_000, controller.observedPeak.value.bytesPerSecond)
  }

  @Test
  fun observedPeak_inSlowLane_ignoresSpeed() = runTest {
    val speed = MutableStateFlow(0L)
    val settings = SpeedSettings(mode = SpeedLimitMode.SlowLane)
    val controller = controller(Device(), settings = settings, speed = speed)

    speed.value = 5_000
    runCurrent()

    assertEquals(ObservedPeak(), controller.observedPeak.value)
  }
}
