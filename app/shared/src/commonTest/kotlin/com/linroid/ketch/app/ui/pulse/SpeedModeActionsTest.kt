package com.linroid.ketch.app.ui.pulse

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.config.KetchConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class SpeedModeActionsTest {

  /** The embedded engine: it keeps the config it is given, or rejects it while [rejects]. */
  private class Engine : KetchApi by FakeKetchApi() {
    var config = DownloadConfig()
    var rejects = false

    override suspend fun updateConfig(config: DownloadConfig) {
      if (rejects) throw IllegalStateException("Engine stopped")
      this.config = config
    }
  }

  private class Fixture(val state: AppState, val engine: Engine, val speed: SpeedModeController?)

  private fun TestScope.fixture(withSpeedMode: Boolean = true): Fixture {
    val engine = Engine()
    val store = RecordingConfigStore(KetchConfig())
    val speed = if (withSpeedMode) {
      SpeedModeController(
        config = { engine.config },
        apply = { engine.updateConfig(it) },
        scope = backgroundScope,
      )
    } else {
      null
    }
    val state = AppState(
      instanceManager = InstanceManager(
        factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { engine }),
        configStore = store,
      ),
      scope = backgroundScope,
      appSettings = AppSettingsController(store),
      speedMode = speed,
    )
    return Fixture(state, engine, speed)
  }

  @Test
  fun toggleSlowLane_fullSpeed_turnsItOnWithUndo() = runTest {
    val f = fixture()

    f.state.toggleSlowLane()
    runCurrent()

    assertEquals(SpeedMode.SlowLane, f.speed?.mode?.value)
    assertEquals(SpeedLimit.mbps(1), f.engine.config.speedLimit)
    val message = f.state.messages.history.value.first()
    assertEquals("Slow lane on · 1 MB/s", message.title.load())
    assertEquals(listOf("Undo"), message.actions.map { it.label }.load())
  }

  @Test
  fun toggleSlowLane_undo_restoresFullSpeed() = runTest {
    val f = fixture()
    f.state.toggleSlowLane()
    runCurrent()

    f.state.messages.history.value.first().actions.single().onClick()
    runCurrent()

    assertEquals(SpeedMode.Full, f.speed?.mode?.value)
    assertEquals(SpeedLimit.Unlimited, f.engine.config.speedLimit)
    val message = f.state.messages.history.value.first()
    assertEquals("Slow lane off", message.title.load())
    assertTrue(message.actions.isEmpty())
  }

  @Test
  fun toggleSlowLane_withoutSpeedMode_doesNothing() = runTest {
    val f = fixture(withSpeedMode = false)

    assertNull(f.state.toggleSlowLane())
    assertTrue(f.state.messages.history.value.isEmpty())
  }

  @Test
  fun switchSpeedMode_engineRejects_keepsTheModeAndOffersTryAgain() = runTest {
    val f = fixture()
    f.engine.rejects = true

    f.state.toggleSlowLane()
    runCurrent()

    assertEquals(SpeedMode.Full, f.speed?.mode?.value)
    val message = f.state.messages.history.value.first()
    assertEquals(MessageLevel.Error, message.level)
    assertEquals("Couldn't switch to Slow lane", message.title.load())
    assertEquals(listOf("Try again"), message.actions.map { it.label }.load())

    f.engine.rejects = false
    message.actions.single().onClick()
    runCurrent()
    assertEquals(SpeedMode.SlowLane, f.speed?.mode?.value)
  }

  @Test
  fun setSpeedLimit_asSlowLaneAtFullSpeed_setsItsSpeedAndTurnsItOn() = runTest {
    val f = fixture()

    f.state.setSpeedLimit(SpeedLimit.mbps(2), asSlowLane = true)
    runCurrent()

    assertEquals(SpeedMode.SlowLane, f.speed?.mode?.value)
    assertEquals(SpeedLimit.mbps(2), f.speed?.settings?.value?.slowLane)
    assertEquals(SpeedLimit.mbps(2), f.engine.config.speedLimit)
  }

  @Test
  fun setSpeedLimit_capAtFullSpeed_becomesTheDownloadLimit() = runTest {
    val f = fixture()

    f.state.setSpeedLimit(SpeedLimit.mbps(5), asSlowLane = false)
    runCurrent()

    assertEquals(SpeedMode.Full, f.speed?.mode?.value)
    assertEquals(SpeedLimit.mbps(5), f.engine.config.speedLimit)
    assertEquals(SpeedLimit.mbps(5), f.state.instanceSettings.download?.speedLimit)
  }

  @Test
  fun limitGoesToSettings_capAtFullSpeedOrNoSpeedMode_isTrue() = runTest {
    val f = fixture()

    assertTrue(f.state.limitGoesToSettings(asSlowLane = false))
    assertFalse(f.state.limitGoesToSettings(asSlowLane = true))
    assertTrue(fixture(withSpeedMode = false).state.limitGoesToSettings(asSlowLane = true))
  }

  @Test
  fun setSpeedLimit_capInSlowLane_waitsForFullSpeed() = runTest {
    val f = fixture()
    f.state.toggleSlowLane()
    runCurrent()

    f.state.setSpeedLimit(SpeedLimit.mbps(20), asSlowLane = false)
    runCurrent()

    assertEquals(SpeedLimit.mbps(1), f.engine.config.speedLimit)
    f.state.toggleSlowLane()
    runCurrent()
    assertEquals(SpeedLimit.mbps(20), f.engine.config.speedLimit)
  }
}
