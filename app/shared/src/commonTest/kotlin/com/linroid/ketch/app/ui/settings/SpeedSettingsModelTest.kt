package com.linroid.ketch.app.ui.settings

import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SpeedLimitMode
import com.linroid.ketch.api.SpeedModeChange
import com.linroid.ketch.api.SpeedSettings
import com.linroid.ketch.app.FakeInstanceFactory
import com.linroid.ketch.app.FakeSpeedModes
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.SpeedModeEngine
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.settleSnapshots
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.PulseModel
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.RemoteConfig
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class SpeedSettingsModelTest {

  private class Fixture(val engine: SpeedModeEngine, val state: AppState) {
    val embedded: InstanceEntry get() = state.instances.value.first()
    val remote: RemoteInstance
      get() = state.instances.value.filterIsInstance<RemoteInstance>().single()

    fun model(device: InstanceEntry = embedded) = SpeedSettingsModel(state, device)
  }

  private val now = Instant.parse("2026-10-01T14:30:00Z")
  private val clock = object : Clock {
    override fun now(): Instant = now
  }

  // The embedded device in mode, and remote devices running remoteModes when given.
  private fun TestScope.fixture(
    mode: SpeedLimitMode,
    remotes: List<RemoteConfig> = emptyList(),
    remoteModes: FakeSpeedModes? = null,
  ): Fixture {
    val settings = SpeedSettings(mode = mode, slowLane = SpeedLimit.mbps(1))
    val engine = SpeedModeEngine(settings, clock)
    val store = RecordingConfigStore(KetchConfig(download = engine.config))
    val fakes = FakeInstanceFactory(embeddedFactory = { engine })
    fakes.speedModes = remoteModes
    val manager = InstanceManager(
      factory = fakes.factory,
      initialRemotes = remotes,
      configStore = store,
      context = backgroundScope.coroutineContext,
    )
    val state = AppState(
      instanceManager = manager,
      scope = backgroundScope,
      appSettings = AppSettingsController(store),
    )
    manager.presence
    // Past the device totals' throttle, so presence lists every device.
    advanceTimeBy(PulseModel.UPDATE_INTERVAL + 1.seconds)
    settleSnapshots()
    return Fixture(engine, state)
  }

  @Test
  fun setSlowLane_inSlowLane_updatesThePulseCap() = runTest {
    val fixture = fixture(SpeedLimitMode.SlowLane)
    assertEquals(SpeedLimit.mbps(1), fixture.state.pulse.state.value.cap)

    fixture.model().setSlowLane(SpeedLimit.mbps(2))
    settleSnapshots()

    assertEquals(SpeedLimit.mbps(2), fixture.engine.config.speedLimit)
    assertEquals(SpeedLimit.mbps(2), fixture.state.pulse.state.value.cap)
  }

  @Test
  fun setFullSpeedCap_inSlowLane_keepsTheSlowLaneUntilFullSpeed() = runTest {
    val fixture = fixture(SpeedLimitMode.SlowLane)
    val model = fixture.model()

    model.setFullSpeedCap(SpeedLimit.mbps(10))
    settleSnapshots()
    assertEquals(SpeedLimit.mbps(1), fixture.engine.config.speedLimit)

    model.setMode(SpeedLimitMode.Full)
    settleSnapshots()
    assertEquals(SpeedLimit.mbps(10), fixture.engine.config.speedLimit)
    assertEquals(SpeedLimit.mbps(10), fixture.state.pulse.state.value.cap)
  }

  @Test
  fun setMode_deviceRefuses_postsAnErrorAndKeepsTheMode() = runTest {
    val fixture = fixture(SpeedLimitMode.Full)
    fixture.engine.rejects = true
    val model = fixture.model()

    model.setMode(SpeedLimitMode.SlowLane)
    settleSnapshots()

    assertEquals(SpeedLimitMode.Full, assertNotNull(model.speedMode).settings.value.mode)
    val error = fixture.state.messages.active.value.single { it.level == MessageLevel.Error }
    assertTrue(error.title.load().contains("Slow lane"), error.title.load())
  }

  @Test
  fun setMode_remoteDeviceWithSpeedModes_switchesThatDevice() = runTest {
    val nas = FakeSpeedModes(clock = clock)
    val fixture = fixture(
      SpeedLimitMode.Full,
      remotes = listOf(RemoteConfig("nas")),
      remoteModes = nas,
    )
    val model = fixture.model(fixture.remote)

    model.setMode(SpeedLimitMode.SlowLane)
    settleSnapshots()

    assertEquals(listOf(SpeedModeChange(mode = SpeedLimitMode.SlowLane)), nas.changes)
    assertEquals(emptyList(), assertNotNull(fixture.engine.speedModes).changes)
    val presence = fixture.state.instanceManager.presence.value
    assertEquals(SpeedMode.SlowLane, presence.single { it.entry is RemoteInstance }.speedMode)
  }

  @Test
  fun readSpeedMode_remoteNotKeptConnected_readsAndSwitchesIt() = runTest {
    val nas = FakeSpeedModes(SpeedSettings(mode = SpeedLimitMode.SlowLane), clock = clock)
    val fixture = fixture(
      SpeedLimitMode.Full,
      remotes = listOf(RemoteConfig("nas", watch = false)),
      remoteModes = nas,
    )
    assertNull(fixture.model(fixture.remote).speedMode)

    val read = fixture.state.instanceManager.readSpeedMode(fixture.remote, backgroundScope)
    runCurrent()
    assertEquals(SpeedMode.SlowLane, assertNotNull(read).mode.value)

    SpeedSettingsModel(fixture.state, fixture.remote, read).setMode(SpeedLimitMode.Full)
    settleSnapshots()
    assertEquals(listOf(SpeedModeChange(mode = SpeedLimitMode.Full)), nas.changes)
    assertEquals(SpeedMode.Full, read.mode.value)
  }

  @Test
  fun speedMode_remoteDeviceWithoutSpeedModes_isNone() = runTest {
    val fixture = fixture(SpeedLimitMode.Full, remotes = listOf(RemoteConfig("nas")))

    assertNull(fixture.model(fixture.remote).speedMode)
  }
}
