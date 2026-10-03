package com.linroid.ketch.app.ui.devices

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.fixtureTest
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.platform.DroppedFile
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.DeviceHealth
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.RecordingKetchApi
import com.linroid.ketch.app.state.SpeedMode
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.StatusFilter
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.ui.pulse.setSpeedLimit
import com.linroid.ketch.app.ui.shell.FleetFixtures.presence
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class DeviceActionsTest {

  /** The embedded engine: it keeps the config it is given. */
  private class Engine : KetchApi by FakeKetchApi() {
    var config = DownloadConfig()

    override suspend fun updateConfig(config: DownloadConfig) {
      this.config = config
    }
  }

  private class Fixture(
    val controller: AppController,
    val nas: InstanceEntry,
    val speed: SpeedModeController,
  ) {
    val state get() = controller.state
  }

  private fun TestScope.fixture(nasApi: KetchApi): Fixture {
    val engine = Engine()
    val speed = SpeedModeController(
      config = { engine.config },
      apply = { engine.updateConfig(it) },
      scope = backgroundScope,
    )
    val manager = InstanceManager(
      factory = InstanceFactory(
        deviceName = "MacBook Pro",
        embeddedFactory = { engine },
        remoteFactory = { config ->
          RemoteInstance(nasApi, config, MutableStateFlow(ConnectionState.Connected))
        },
      ),
      initialRemotes = listOf(RemoteConfig(host = "nas.local", name = "NAS-Basement")),
      context = backgroundScope.coroutineContext,
    )
    val controller = AppController(
      instanceManager = manager,
      context = StandardTestDispatcher(testScheduler),
      speedMode = speed,
    )
    return Fixture(controller, manager.instances.value.first { it is RemoteInstance }, speed)
  }

  private fun devicesTest(block: suspend TestScope.(Fixture) -> Unit) =
    fixtureTest({ fixture(RecordingKetchApi("NAS")) }, { it.controller.close() }, block)

  @Test
  fun showDeviceTab_failedCountOfTheNas_switchesToItsFailedTab() = runTest {
    val nas = RecordingKetchApi("NAS")
    nas.add(DownloadState.Failed(KetchError.Network()))
    val f = fixture(nas)
    var shown = 0
    backgroundScope.launch { f.state.downloadsRequests.collect { shown++ } }
    runCurrent()

    f.state.showDeviceTab(f.nas, StatusFilter.Failed)
    runCurrent()

    assertEquals(f.nas.deviceId, f.state.activeInstance.value?.deviceId)
    assertEquals(StatusFilter.Failed, f.state.statusFilter)
    assertEquals(1, shown)
    f.controller.close()
  }

  @Test
  fun runNextAction_retryFailedOnTheNas_retriesWithoutSwitching() = runTest {
    val nas = RecordingKetchApi("NAS")
    val failed = nas.add(DownloadState.Failed(KetchError.Network()))
    val f = fixture(nas)

    f.state.runNextAction(f.nas, NextAction.RetryFailed(1))
    runCurrent()

    assertEquals(listOf("resume"), failed.calls)
    assertEquals(LOCAL_DEVICE_ID, f.state.activeInstance.value?.deviceId)
    f.controller.close()
  }

  @Test
  fun runNextAction_pauseAllOnTheNas_pausesOnlyThere() = runTest {
    val nas = RecordingKetchApi("NAS")
    val waiting = nas.add(DownloadState.Queued)
    val f = fixture(nas)

    f.state.runNextAction(f.nas, NextAction.PauseAll)
    runCurrent()

    assertEquals(listOf("pause"), waiting.calls)
    assertEquals(LOCAL_DEVICE_ID, f.state.activeInstance.value?.deviceId)
    f.controller.close()
  }

  @Test
  fun setSpeedLimit_slowLaneOfThisMacWhileTheNasShows_turnsItOn() = devicesTest { f ->
    f.state.switchInstance(f.nas)
    runCurrent()
    val local = f.state.instances.value.first { it.deviceId == LOCAL_DEVICE_ID }

    f.state.setSpeedLimit(local, SpeedLimit.mbps(2), asSlowLane = true)
    runCurrent()

    assertEquals(SpeedMode.SlowLane, f.speed.mode.value)
    assertEquals(SpeedLimit.mbps(2), f.speed.slowLaneSpeed)
  }

  @Test
  fun toggleSlowLane_thisMacWhileTheNasShows_switchesItsModeWithUndo() = devicesTest { f ->
    f.state.switchInstance(f.nas)
    runCurrent()
    val local = f.state.instances.value.first { it.deviceId == LOCAL_DEVICE_ID }

    f.state.toggleSlowLane(presence(local, "This Mac", "MacBook Pro", DeviceHealth.Local()))
    runCurrent()

    assertEquals(SpeedMode.SlowLane, f.speed.mode.value)
    val message = f.state.messages.active.value.last()
    assertTrue(f.state.messages.history.value.isEmpty())
    assertEquals(0, f.state.messages.unreadCount.value)
    assertEquals("Slow lane on · 1 MB/s", message.title.load())
    message.actions.single { it.label.load() == "Undo" }.onClick()
    runCurrent()
    assertEquals(SpeedMode.Full, f.speed.mode.value)
  }

  @Test
  fun addDroppedText_onTheNasCard_opensTheAddSheetForTheNas() = devicesTest { f ->
    f.state.addDroppedText("  https://example.com/ubuntu.iso\n", f.nas)

    assertTrue(f.state.showAddDialog)
    assertEquals(
      IntakeRequest(text = "https://example.com/ubuntu.iso", targetDeviceId = f.nas.deviceId),
      f.state.intakeRequest
    )
  }

  @Test
  fun addDroppedFiles_torrentOnTheNasCard_resolvesItOnTheNas() = runTest {
    val nas = FakeKetchApi().apply {
      resolveContentResult = ResolvedSource(
        url = "magnet:?xt=urn:btih:abc",
        sourceType = "torrent",
        totalBytes = 1_000,
        supportsResume = true,
        suggestedFileName = "ubuntu.iso",
        maxSegments = 1,
      )
    }
    val f = fixture(nas)
    val file = DroppedFile("ubuntu.torrent") { byteArrayOf(1, 2, 3) }

    f.state.addDroppedFiles(listOf(file), f.nas)
    runCurrent()

    assertEquals(f.nas.deviceId, f.state.intakeRequest?.targetDeviceId)
    assertEquals("ubuntu.torrent", nas.lastResolvedFileName)
    assertSame(nas, f.state.droppedFileApi)
    f.controller.close()
  }

  @Test
  fun renameDevice_nas_renamesItAtOnce() = devicesTest { f ->
    f.state.renameDevice(f.nas, "Basement")

    assertEquals("Basement", f.state.instances.value.first { it is RemoteInstance }.label)
  }
}
