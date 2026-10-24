package com.linroid.ketch.app.state

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SpeedLimitMode
import com.linroid.ketch.api.SpeedModeChange
import com.linroid.ketch.api.SpeedModeState
import com.linroid.ketch.api.SpeedRule
import com.linroid.ketch.api.SpeedSettings
import com.linroid.ketch.app.FakeSpeedModes
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.time.Clock
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedModeControllerTest {

  private val now = Instant.parse("2026-09-30T10:15:00Z")
  private val clock = object : Clock {
    override fun now(): Instant = now
  }

  private class Fixture(
    val device: FakeSpeedModes,
    val reports: MutableSharedFlow<SpeedModeState>,
    val controller: SpeedModeController,
  )

  // A controller whose device reports only what the test sends through reports.
  private fun TestScope.fixture(settings: SpeedSettings = SpeedSettings()): Fixture {
    val device = FakeSpeedModes(settings, clock = clock)
    val reports = MutableSharedFlow<SpeedModeState>()
    val controller = SpeedModeController(device, reports, backgroundScope, "nas:8642")
    runCurrent()
    return Fixture(device, reports, controller)
  }

  @Test
  fun init_beforeTheDeviceReports_showsFullSpeedWithoutState() = runTest {
    val fixture = fixture()

    assertNull(fixture.controller.state.value)
    assertEquals(SpeedMode.Full, fixture.controller.mode.value)
  }

  @Test
  fun reports_autoRuleInEffect_showsAutoInTheSlowLane() = runTest {
    val fixture = fixture()
    val rule = SpeedRule(start = "10:00", end = "10:30")

    fixture.reports.emit(
      SpeedModeState(
        settings = SpeedSettings(mode = SpeedLimitMode.Auto, rules = listOf(rule)),
        inSlowLane = true,
        until = Instant.parse("2026-09-30T10:30:00Z"),
      ),
    )

    val expected = SpeedMode.Auto(slowLane = true, until = Instant.parse("2026-09-30T10:30:00Z"))
    assertEquals(expected, fixture.controller.mode.value)
    assertEquals(listOf(rule), fixture.controller.settings.value.rules)
  }

  @Test
  fun setMode_beforeTheDeviceReports_showsWhatTheChangeReturned() = runTest {
    val fixture = fixture()

    fixture.controller.setMode(SpeedLimitMode.SlowLane)

    assertEquals(SpeedMode.SlowLane, fixture.controller.mode.value)
    assertEquals(SpeedLimit.mbps(1), fixture.controller.state.value?.limit)
  }

  @Test
  fun setMode_deviceRefuses_keepsTheModeAndThrows() = runTest {
    val fixture = fixture()
    fixture.device.failure = IllegalStateException("Connection lost")

    assertFailsWith<IllegalStateException> {
      fixture.controller.setMode(SpeedLimitMode.SlowLane)
    }

    assertEquals(SpeedMode.Full, fixture.controller.mode.value)
  }

  @Test
  fun toggleSlowLane_duringAutoRule_switchesToFullSpeed() = runTest {
    val rule = SpeedRule(start = "10:00", end = "10:30")
    val fixture = fixture(SpeedSettings(mode = SpeedLimitMode.Auto, rules = listOf(rule)))
    fixture.reports.emit(fixture.device.state())

    assertEquals(SpeedLimitMode.Full, fixture.controller.toggleSlowLane())

    assertEquals(listOf(SpeedModeChange(mode = SpeedLimitMode.Full)), fixture.device.changes)
  }

  @Test
  fun setSlowLane_null_asksForTheSuggestion() = runTest {
    val fixture = fixture(SpeedSettings(slowLane = SpeedLimit.mbps(2)))

    fixture.controller.setSlowLane(null)

    assertEquals(SpeedModeChange(slowLane = SpeedLimit.Unlimited), fixture.device.changes.single())
    assertNull(fixture.controller.settings.value.slowLane)
  }
}
