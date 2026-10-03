package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.instance.DeviceScope
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.platform.localDeviceNoun
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.SpeedModeController
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.state.speedLimitText
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.config.SpeedLimitMode
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopCommandsTest {
  private val downloading = DownloadState.Downloading(DownloadProgress(10, 100, 50))

  @Test
  fun perform_pauseAllOnTheNas_pausesItsTasksWithoutShowingTheWindow() = fleetTest { fleet ->
    val here = fleet.mac.add(downloading)
    val there = fleet.nas.add(downloading)

    fleet.commands.perform(MenuAction.PauseAll(listOf(NAS_ID)))
    runCurrent()

    assertIs<DownloadState.Paused>(there.state.value)
    assertIs<DownloadState.Downloading>(here.state.value)
    assertEquals(0, fleet.windowShown)
  }

  @Test
  fun perform_addClipboardLinkOnTheNas_addsItThereAndKeepsThisComputerShown() =
    fleetTest(clipboard = "https://example.com/ubuntu.iso") { fleet ->
      fleet.commands.perform(MenuAction.AddClipboardLink(NAS_ID))
      runCurrent()

      assertEquals(listOf("https://example.com/ubuntu.iso"), fleet.nas.requests.map { it.url })
      assertTrue(fleet.mac.requests.isEmpty())
      assertEquals(LOCAL_ID, fleet.manager.activeInstance.value?.deviceId)
      assertEquals(0, fleet.windowShown)
    }

  @Test
  fun perform_showDevice_showsTheWindowOnThatDevice() = fleetTest { fleet ->
    fleet.commands.perform(MenuAction.ShowDevice(NAS_ID))
    runCurrent()

    assertEquals(NAS_ID, fleet.manager.activeInstance.value?.deviceId)
    assertEquals(1, fleet.windowShown)
  }

  @Test
  fun perform_stayConnectedOff_stopsWatchingTheNas() = fleetTest { fleet ->
    fleet.commands.perform(MenuAction.StayConnected(NAS_ID, watch = false))
    runCurrent()

    val nas = fleet.manager.instances.value.single { it.deviceId == NAS_ID } as RemoteInstance
    assertEquals(false, nas.remoteConfig.watch)
  }

  @Test
  fun perform_retryNow_connectsTheNasWithAFreshClient() = fleetTest { fleet ->
    val clients = fleet.nasClients

    fleet.commands.perform(MenuAction.Reconnect(NAS_ID))
    runCurrent()

    assertEquals(clients + 1, fleet.nasClients)
  }

  @Test
  fun perform_enterToken_opensTheAddDeviceSheetForTheNas() = fleetTest { fleet ->
    fleet.commands.perform(MenuAction.EnterToken(NAS_ID))

    assertEquals(NAS_ID, fleet.controller.state.unauthorizedInstance?.deviceId)
    assertTrue(fleet.controller.state.showAddRemoteDialog)
    assertEquals(1, fleet.windowShown)
  }

  @Test
  fun perform_setSpeedLimitOnTheNas_appliesItAsItsDownloadSetting() = fleetTest { fleet ->
    fleet.commands.perform(MenuAction.SetSpeedLimit(NAS_ID, SpeedLimit.mbps(2)))
    runCurrent()

    assertEquals(SpeedLimit.mbps(2), fleet.nas.configs.last().speedLimit)
    assertTrue(fleet.mac.configs.isEmpty())
  }

  @Test
  fun perform_slowLaneOnThisComputerWhileTheNasShows_switchesItWithUndo() =
    fleetTest(speedMode = true) { fleet ->
      val speedMode = checkNotNull(fleet.controller.speedMode)
      fleet.controller.state.switchInstance(fleet.manager.instances.value.last())
      runCurrent()

      fleet.commands.perform(MenuAction.SetSpeedMode(SpeedLimitMode.SlowLane))
      runCurrent()

      assertEquals(SpeedLimitMode.SlowLane, speedMode.settings.value.mode)
      val message = fleet.messages().last()
      val speed = speedLimitText(speedMode.slowLaneSpeed).load()
      assertEquals("Slow lane on for ${localDeviceNoun().load()} · $speed", message.title.load())
      message.actions.single { it.label.load() == "Undo" }.onClick()
      runCurrent()
      assertEquals(SpeedLimitMode.Full, speedMode.settings.value.mode)
    }

  @Test
  fun run_deviceCommand_showsTheWindowOnThatDevice() = fleetTest { fleet ->
    fleet.commands.run(KetchCommands.device(2))
    runCurrent()

    assertEquals(NAS_ID, fleet.manager.activeInstance.value?.deviceId)
    assertEquals(1, fleet.windowShown)
  }

  @Test
  fun run_allDevices_showsTheWindowWithEveryDevice() = fleetTest { fleet ->
    fleet.commands.run(KetchCommands.AllDevices)

    assertEquals(DeviceScope.All, fleet.manager.deviceScope.value)
    assertEquals(1, fleet.windowShown)
  }

  @Test
  fun run_palette_showsTheWindowAndAsksItsShell() = fleetTest { fleet ->
    fleet.commands.run(KetchCommands.Palette)

    assertEquals(KetchCommands.Palette, fleet.controller.state.shellCommand)
    assertEquals(1, fleet.windowShown)
  }

  @Test
  fun run_shortcuts_showsTheWindowWithTheShortcutSheet() = fleetTest { fleet ->
    fleet.commands.run(KetchCommands.Shortcuts)

    assertTrue(fleet.controller.state.shortcutsRequested)
    assertEquals(1, fleet.windowShown)
  }

  private fun fleetTest(
    clipboard: String? = null,
    speedMode: Boolean = false,
    block: suspend TestScope.(Fleet) -> Unit,
  ) = runTest {
    val fleet = Fleet(this, clipboard, speedMode)
    try {
      runCurrent()
      advanceTimeBy(1.seconds)
      block(fleet)
    } finally {
      fleet.close()
    }
  }

  /** This computer and a connected NAS, with the app and the menu commands over them. */
  private class Fleet(scope: TestScope, clipboard: String?, speedMode: Boolean) {
    val mac = FakeDevice()
    val nas = FakeDevice()
    var nasClients = 0
      private set
    var windowShown = 0
      private set

    val manager = InstanceManager(
      factory = InstanceFactory(
        deviceName = "Lins-MacBook-Pro",
        embeddedFactory = { mac },
        remoteFactory = { config ->
          nasClients++
          RemoteInstance(nas, config, MutableStateFlow(ConnectionState.Connected))
        },
      ),
      initialRemotes = listOf(RemoteConfig(host = "nas.local", name = "NAS")),
      context = scope.backgroundScope.coroutineContext,
    )

    // A child of the background scope, so a failed test never leaves its loops running.
    val controller = AppController(
      instanceManager = manager,
      context = scope.backgroundScope.coroutineContext +
        SupervisorJob(scope.backgroundScope.coroutineContext[Job]),
      speedMode = if (speedMode) {
        SpeedModeController(
          config = { mac.config },
          apply = mac::updateConfig,
          scope = scope.backgroundScope,
        )
      } else {
        null
      },
    )

    val commands = DesktopCommands(
      controller = controller,
      actions = DesktopActions(
        showWindow = { windowShown++ },
        closeWindow = {},
        quit = {},
        openFiles = {},
      ),
      speedMode = controller.speedMode,
      files = null,
      clipboard = FakeClipboard(clipboard),
    )

    fun messages(): List<AppMessage> = controller.messages.history.value

    fun close() {
      controller.close()
      manager.close()
    }
  }

  private class FakeClipboard(private val text: String?) : SystemClipboard {
    override val readsSilently: Boolean = true
    override val pasteEvents: Flow<String> = emptyFlow()

    override suspend fun hasLink(): Boolean = text != null

    override suspend fun readText(): String? = text

    override suspend fun writeText(text: String) {}
  }

  private companion object {
    const val LOCAL_ID = "local"
    const val NAS_ID = "nas.local:8642"
  }
}
