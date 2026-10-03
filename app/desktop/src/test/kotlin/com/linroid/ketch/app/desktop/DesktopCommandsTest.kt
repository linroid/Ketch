package com.linroid.ketch.app.desktop

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SystemInfo
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
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class DesktopCommandsTest {
  private val downloading = DownloadState.Downloading(DownloadProgress(10, 100, 50))

  @Test
  fun perform_pauseAllOnTheNas_pausesItsTasksWithoutShowingTheWindow() = runTest {
    val fleet = fleet()
    val here = fleet.mac.add(downloading)
    val there = fleet.nas.add(downloading)

    fleet.commands.perform(MenuAction.PauseAll(listOf(NAS_ID)))
    runCurrent()

    assertIs<DownloadState.Paused>(there.state.value)
    assertIs<DownloadState.Downloading>(here.state.value)
    assertEquals(0, fleet.windowShown)
    fleet.close()
  }

  @Test
  fun perform_addClipboardLinkOnTheNas_addsItThereAndKeepsThisComputerShown() = runTest {
    val fleet = fleet(clipboard = "https://example.com/ubuntu.iso")

    fleet.commands.perform(MenuAction.AddClipboardLink(NAS_ID))
    runCurrent()

    assertEquals(listOf("https://example.com/ubuntu.iso"), fleet.nas.requests.map { it.url })
    assertTrue(fleet.mac.requests.isEmpty())
    assertEquals(LOCAL_ID, fleet.manager.activeInstance.value?.deviceId)
    assertEquals(0, fleet.windowShown)
    fleet.close()
  }

  @Test
  fun perform_showDevice_showsTheWindowOnThatDevice() = runTest {
    val fleet = fleet()

    fleet.commands.perform(MenuAction.ShowDevice(NAS_ID))
    runCurrent()

    assertEquals(NAS_ID, fleet.manager.activeInstance.value?.deviceId)
    assertEquals(1, fleet.windowShown)
    fleet.close()
  }

  @Test
  fun perform_stayConnectedOff_stopsWatchingTheNas() = runTest {
    val fleet = fleet()

    fleet.commands.perform(MenuAction.StayConnected(NAS_ID, watch = false))
    runCurrent()

    val nas = fleet.manager.instances.value.single { it.deviceId == NAS_ID } as RemoteInstance
    assertEquals(false, nas.remoteConfig.watch)
    fleet.close()
  }

  @Test
  fun perform_retryNow_connectsTheNasWithAFreshClient() = runTest {
    val fleet = fleet()
    val clients = fleet.nasClients

    fleet.commands.perform(MenuAction.Reconnect(NAS_ID))
    runCurrent()

    assertEquals(clients + 1, fleet.nasClients)
    fleet.close()
  }

  @Test
  fun perform_enterToken_opensTheAddDeviceSheetForTheNas() = runTest {
    val fleet = fleet()

    fleet.commands.perform(MenuAction.EnterToken(NAS_ID))

    assertEquals(NAS_ID, fleet.controller.state.unauthorizedInstance?.deviceId)
    assertTrue(fleet.controller.state.showAddRemoteDialog)
    assertEquals(1, fleet.windowShown)
    fleet.close()
  }

  @Test
  fun perform_setSpeedLimitOnTheNas_appliesItAsItsDownloadSetting() = runTest {
    val fleet = fleet()

    fleet.commands.perform(MenuAction.SetSpeedLimit(NAS_ID, SpeedLimit.mbps(2)))
    runCurrent()

    assertEquals(SpeedLimit.mbps(2), fleet.nas.configs.last().speedLimit)
    assertTrue(fleet.mac.configs.isEmpty())
    fleet.close()
  }

  @Test
  fun perform_slowLaneOnThisComputerWhileTheNasShows_switchesItWithUndo() = runTest {
    val fleet = fleet(speedMode = true)
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
    fleet.close()
  }

  @Test
  fun run_deviceCommand_showsTheWindowOnThatDevice() = runTest {
    val fleet = fleet()

    fleet.commands.run(KetchCommands.device(2))
    runCurrent()

    assertEquals(NAS_ID, fleet.manager.activeInstance.value?.deviceId)
    assertEquals(1, fleet.windowShown)
    fleet.close()
  }

  @Test
  fun run_allDevices_showsTheWindowWithEveryDevice() = runTest {
    val fleet = fleet()

    fleet.commands.run(KetchCommands.AllDevices)

    assertEquals(DeviceScope.All, fleet.manager.deviceScope.value)
    assertEquals(1, fleet.windowShown)
    fleet.close()
  }

  @Test
  fun run_palette_showsTheWindowAndAsksItsShell() = runTest {
    val fleet = fleet()

    fleet.commands.run(KetchCommands.Palette)

    assertEquals(KetchCommands.Palette, fleet.controller.state.shellCommand)
    assertEquals(1, fleet.windowShown)
    fleet.close()
  }

  @Test
  fun run_shortcuts_showsTheWindowWithTheShortcutSheet() = runTest {
    val fleet = fleet()

    fleet.commands.run(KetchCommands.Shortcuts)

    assertTrue(fleet.controller.state.shortcutsRequested)
    assertEquals(1, fleet.windowShown)
    fleet.close()
  }

  private fun TestScope.fleet(clipboard: String? = null, speedMode: Boolean = false): Fleet =
    Fleet(this, clipboard, speedMode).also {
      runCurrent()
      advanceTimeBy(1.seconds)
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

  /** A device that records the downloads and the settings it is given. */
  private class FakeDevice : KetchApi {
    private val list = MutableStateFlow<List<DownloadTask>>(emptyList())

    /** Requests passed to [download], in order. */
    val requests = mutableListOf<DownloadRequest>()

    /** Configs passed to [updateConfig], in order. */
    val configs = mutableListOf<DownloadConfig>()

    var config = DownloadConfig()
      private set

    override val backendLabel: String = "Fake"
    override val tasks: StateFlow<List<DownloadTask>> = list

    /** Adds a task in [state]. */
    fun add(
      state: DownloadState,
      request: DownloadRequest = DownloadRequest("https://example.com/${list.value.size}.bin"),
    ): FakeTask = FakeTask("t${list.value.size}", request, state).also { task ->
      list.update { it + task }
    }

    override suspend fun download(request: DownloadRequest): DownloadTask {
      requests += request
      return add(DownloadState.Queued, request)
    }

    override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
      error("Not used")

    override suspend fun start() {}

    override suspend fun status(): KetchStatus = KetchStatus(
      name = "Fake",
      version = "0.0.1",
      revision = "test",
      uptime = 0,
      config = config,
      system = SYSTEM,
    )

    override suspend fun updateConfig(config: DownloadConfig) {
      configs += config
      this.config = config
    }

    override fun close() {}
  }

  /** A task that changes state as it is told to. */
  private class FakeTask(
    override val taskId: String,
    request: DownloadRequest,
    initial: DownloadState,
  ) : DownloadTask {
    override val requestState = MutableStateFlow(request)
    override val request: DownloadRequest get() = requestState.value
    override val createdAt: Instant = Instant.fromEpochSeconds(0)
    override val state = MutableStateFlow(initial)
    override val segments = MutableStateFlow(emptyList<Segment>())

    override suspend fun pause() {
      state.value = DownloadState.Paused(DownloadProgress(10, 100))
    }

    override suspend fun resume(destination: Destination?) {
      state.value = DownloadState.Queued
    }

    override suspend fun cancel() {
      state.value = DownloadState.Canceled
    }

    override suspend fun setSpeedLimit(limit: SpeedLimit) {}

    override suspend fun setPriority(priority: DownloadPriority) {}

    override suspend fun setConnections(connections: Int) {}

    override suspend fun reschedule(
      schedule: DownloadSchedule,
      conditions: List<DownloadCondition>,
    ) {}

    override suspend fun remove(deleteFiles: Boolean) {}
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
    val SYSTEM = SystemInfo(
      os = "Linux",
      arch = "x64",
      separator = "/",
      javaVersion = "21",
      availableProcessors = 4,
      maxMemory = 0,
      totalMemory = 0,
      freeMemory = 0,
      downloadDirectory = "/downloads",
      totalSpace = 0,
      freeSpace = 0,
      usableSpace = 0,
    )
  }
}
