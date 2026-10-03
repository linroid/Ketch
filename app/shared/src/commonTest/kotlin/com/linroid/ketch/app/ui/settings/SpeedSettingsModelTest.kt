package com.linroid.ketch.app.ui.settings

import androidx.compose.runtime.snapshots.Snapshot
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.SpeedScheduler
import com.linroid.ketch.app.testStatus
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.SpeedLimitMode
import com.linroid.ketch.config.SpeedRule
import com.linroid.ketch.config.SpeedSettings
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Instant

class SpeedSettingsModelTest {

  /** The embedded engine: it keeps the config it is given, or refuses it. */
  private class Engine(var config: DownloadConfig) : KetchApi {
    override val backendLabel = "This Mac"
    override val tasks = MutableStateFlow(emptyList<DownloadTask>())
    var refuse = false

    override suspend fun download(request: DownloadRequest): DownloadTask =
      throw UnsupportedOperationException()

    override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
      throw UnsupportedOperationException()

    override suspend fun resolveContent(content: ByteArray, fileName: String?): ResolvedSource =
      throw UnsupportedOperationException()

    override suspend fun status(): KetchStatus = testStatus(backendLabel, config)

    override suspend fun updateConfig(config: DownloadConfig) {
      if (refuse) throw IllegalStateException("The engine said no")
      this.config = config
    }

    override suspend fun start() {}

    override fun close() {}
  }

  private class Fixture(
    val engine: Engine,
    val state: AppState,
    val speedMode: SpeedModeController,
    val model: SpeedSettingsModel,
  )

  private val now = Instant.parse("2026-10-01T14:30:00Z")
  private val clock = object : Clock {
    override fun now(): Instant = now
  }

  private fun TestScope.fixture(
    mode: SpeedLimitMode,
    cap: SpeedLimit = SpeedLimit.Unlimited,
  ): Fixture {
    val slowLane = SpeedLimit.mbps(1)
    val engine = Engine(
      DownloadConfig(speedLimit = if (mode == SpeedLimitMode.SlowLane) slowLane else cap),
    )
    val store = RecordingConfigStore(KetchConfig(download = engine.config))
    val manager = InstanceManager(
      factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { engine }),
      configStore = store,
    )
    val speedMode = SpeedModeController(
      config = { engine.status().config },
      apply = { engine.updateConfig(it) },
      scope = backgroundScope,
      settings = SpeedSettings(mode = mode, standard = cap, slowLane = slowLane),
      scheduler = SpeedScheduler { TimeZone.UTC },
      clock = clock,
    )
    val state = AppState(
      instanceManager = manager,
      scope = backgroundScope,
      appSettings = AppSettingsController(store),
      speedMode = speedMode,
    )
    runCurrent()
    val model = SpeedSettingsModel(state, state.instances.value.single())
    return Fixture(engine, state, speedMode, model)
  }

  private fun TestScope.settle() {
    runCurrent()
    Snapshot.sendApplyNotifications()
    runCurrent()
  }

  @Test
  fun setSlowLane_inSlowLane_updatesThePulseCap() = runTest {
    val fixture = fixture(SpeedLimitMode.SlowLane)
    settle()
    assertEquals(SpeedLimit.mbps(1), fixture.state.pulse.state.value.cap)

    fixture.model.setSlowLane(SpeedLimit.mbps(2))
    settle()

    assertEquals(SpeedLimit.mbps(2), fixture.engine.config.speedLimit)
    assertEquals(SpeedLimit.mbps(2), fixture.state.pulse.state.value.cap)
  }

  @Test
  fun setFullSpeedCap_inSlowLane_keepsTheSlowLaneUntilFullSpeed() = runTest {
    val fixture = fixture(SpeedLimitMode.SlowLane)

    fixture.model.setFullSpeedCap(SpeedLimit.mbps(10))
    settle()

    assertEquals(SpeedLimit.mbps(1), fixture.engine.config.speedLimit)
    assertEquals(SpeedLimit.mbps(10), fixture.speedMode.settings.value.standard)
    fixture.model.setMode(SpeedLimitMode.Full)
    settle()
    assertEquals(SpeedLimit.mbps(10), fixture.engine.config.speedLimit)
    assertEquals(SpeedLimit.mbps(10), fixture.state.pulse.state.value.cap)
  }

  @Test
  fun setFullSpeedCap_atFullSpeed_changesTheDeviceLimit() = runTest {
    val fixture = fixture(SpeedLimitMode.Full)

    fixture.model.setFullSpeedCap(SpeedLimit.mbps(10))
    settle()

    assertEquals(SpeedLimit.mbps(10), fixture.engine.config.speedLimit)
    assertEquals(SpeedLimit.mbps(10), fixture.state.appSettings.config.download.speedLimit)
  }

  @Test
  fun setRules_atFullSpeedAfterSettingTheCap_keepsTheCap() = runTest {
    val fixture = fixture(SpeedLimitMode.Full)
    fixture.model.setFullSpeedCap(SpeedLimit.mbps(10))
    settle()

    fixture.model.setRules(listOf(SpeedRule(start = "14:00", end = "15:00")))
    settle()

    assertEquals(SpeedLimit.mbps(10), fixture.engine.config.speedLimit)
    assertEquals(SpeedLimit.mbps(10), fixture.speedMode.settings.value.standard)
  }

  @Test
  fun setSlowLane_atFullSpeedWithACapSetElsewhere_keepsTheCap() = runTest {
    val fixture = fixture(SpeedLimitMode.Full)
    fixture.engine.config = fixture.engine.config.copy(speedLimit = SpeedLimit.mbps(5))

    fixture.model.setSlowLane(SpeedLimit.mbps(2))
    settle()

    assertEquals(SpeedLimit.mbps(5), fixture.engine.config.speedLimit)
    assertEquals(SpeedLimit.mbps(2), fixture.speedMode.settings.value.slowLane)
    fixture.model.setMode(SpeedLimitMode.SlowLane)
    fixture.model.setMode(SpeedLimitMode.Full)
    settle()
    assertEquals(SpeedLimit.mbps(5), fixture.engine.config.speedLimit)
  }

  @Test
  fun setRules_inAuto_appliesTheSlowLaneOfTheRule() = runTest {
    val fixture = fixture(SpeedLimitMode.Auto)

    fixture.model.setRules(listOf(SpeedRule(start = "14:00", end = "15:00")))
    settle()

    assertEquals(SpeedLimit.mbps(1), fixture.engine.config.speedLimit)
    assertEquals(SpeedLimit.mbps(1), fixture.state.pulse.state.value.cap)
  }

  @Test
  fun setMode_deviceRefuses_postsAnErrorAndKeepsTheMode() = runTest {
    val fixture = fixture(SpeedLimitMode.Full)
    fixture.engine.refuse = true

    fixture.model.setMode(SpeedLimitMode.SlowLane)
    settle()

    assertEquals(SpeedLimitMode.Full, fixture.speedMode.settings.value.mode)
    val error = fixture.state.messages.active.value.single { it.level == MessageLevel.Error }
    assertTrue(error.title.load().contains("Slow lane"), error.title.load())
  }
}
