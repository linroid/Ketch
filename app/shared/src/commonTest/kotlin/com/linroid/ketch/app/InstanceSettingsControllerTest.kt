package com.linroid.ketch.app

import com.linroid.ketch.api.ControlKey
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.state.InstanceSettingsController
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.TorrentEncryptionMode
import com.linroid.ketch.config.TorrentSettings
import com.linroid.ketch.config.TorrentUploadMode
import com.linroid.ketch.torrent.api.TorrentCapabilities
import com.linroid.ketch.torrent.api.TorrentController
import com.linroid.ketch.torrent.api.TorrentEncryptionSetting
import com.linroid.ketch.torrent.api.TorrentEngineSettings
import com.linroid.ketch.torrent.api.TorrentSettingsChange
import com.linroid.ketch.torrent.api.TorrentSnapshot
import com.linroid.ketch.torrent.api.TorrentUploadSetting
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Torrent controls of a remote device that keeps its settings, with [names] as capabilities. */
private class SettingsTorrentController(
  names: Set<String> = setOf("inspect", "settings", "local-discovery"),
  var settings: TorrentEngineSettings = TorrentEngineSettings(),
) : TorrentController {
  private val capabilities = TorrentCapabilities(names = names)
  val changes = mutableListOf<TorrentSettingsChange>()
  var refuse = false

  /** Holds every [updateSettings] until it completes; null lets them through. */
  var gate: CompletableDeferred<Unit>? = null

  override suspend fun capabilities() = capabilities

  override suspend fun snapshot(taskId: String): TorrentSnapshot? = null

  override suspend fun settings() = settings

  override suspend fun updateSettings(change: TorrentSettingsChange): TorrentEngineSettings {
    gate?.await()
    if (refuse) throw IllegalStateException("Server said no")
    changes += change
    settings = change.applyTo(settings)
    return settings
  }
}

/** Instance that records the settings pushed to it. */
private class SettingsKetchApi(
  var config: DownloadConfig = DownloadConfig(),
  var networks: NetworkInterfaces = NetworkInterfaces(),
  private val torrents: TorrentController? = null,
) : KetchApi by FakeKetchApi("Settings") {
  val applied = mutableListOf<DownloadConfig>()
  val networkRequests = mutableListOf<List<String>>()
  var gate: CompletableDeferred<Unit>? = null
  var failUpdates = false

  /** What the instance makes of an accepted config, like a server clamping a value. */
  var accept: (DownloadConfig) -> DownloadConfig = { it }

  override suspend fun status(): KetchStatus = testStatus("remote", config)

  override suspend fun updateConfig(config: DownloadConfig) {
    gate?.await()
    if (failUpdates) throw IllegalStateException("Server said no")
    applied += config
    this.config = accept(config)
  }

  override suspend fun networkInterfaces(): NetworkInterfaces = networks

  @Suppress("UNCHECKED_CAST")
  override fun <T : Any> controller(key: ControlKey<T>): T? =
    if (key === TorrentController.Key) torrents as T? else null

  override suspend fun selectNetworkInterfaces(config: NetworkInterfaceConfig): NetworkInterfaces {
    gate?.await()
    networkRequests += config.interfaceIds
    if (failUpdates) throw IllegalArgumentException("Unknown interface")
    networks = networks.copy(config = config)
    return networks
  }
}

class InstanceSettingsControllerTest {

  @Test
  fun `embedded changes are saved to the config file and applied`() = runTest {
    val store = RecordingConfigStore()
    val api = SettingsKetchApi()
    val controller = InstanceSettingsController(api, AppSettingsController(store), this)
    val edited = DownloadConfig(maxConcurrentDownloads = 5, speedLimit = SpeedLimit.mbps(1))

    controller.updateDownload(edited)
    advanceUntilIdle()

    assertEquals(edited, store.load().download)
    assertEquals(listOf(edited), api.applied)
  }

  @Test
  fun updateDownload_whileSlowLaneHoldsTheLimit_savesTheStandingCap() = runTest {
    val store = RecordingConfigStore()
    val api = SettingsKetchApi()
    val controller = InstanceSettingsController(
      api = api,
      local = AppSettingsController(store),
      scope = this,
      savedSpeedLimit = { SpeedLimit.Unlimited },
    )
    val edited = DownloadConfig(maxConcurrentDownloads = 5, speedLimit = SpeedLimit.mbps(1))

    controller.updateDownload(edited)
    advanceUntilIdle()

    assertEquals(edited.copy(speedLimit = SpeedLimit.Unlimited), store.load().download)
    assertEquals(listOf(edited), api.applied)
  }

  @Test
  fun `embedded change the instance rejects is not saved`() = runTest {
    val store = RecordingConfigStore()
    val api = SettingsKetchApi().apply { failUpdates = true }
    val controller = InstanceSettingsController(api, AppSettingsController(store), this)

    controller.updateDownload(DownloadConfig(defaultDirectory = "/missing"))
    advanceUntilIdle()

    assertEquals(DownloadConfig(), store.load().download)
    assertNotNull(controller.downloadError)
  }

  @Test
  fun `embedded torrent trackers are saved and handed to the instance`() = runTest {
    val store = RecordingConfigStore()
    val applied = mutableListOf<TorrentSettings>()
    val controller = InstanceSettingsController(
      api = SettingsKetchApi(),
      local = AppSettingsController(store),
      scope = this,
      applyTorrent = { applied += it },
    )
    val edited = TorrentSettings(trackers = listOf("udp://tracker.example:1337/announce"))

    controller.updateTorrent(edited)
    advanceUntilIdle()

    assertEquals(edited, store.load().torrent)
    assertEquals(edited, controller.torrent)
    assertEquals(listOf(edited), applied)
  }

  @Test
  fun updateTorrent_uploadModeAndLimit_areSavedAndApplied() = runTest {
    val store = RecordingConfigStore()
    val applied = mutableListOf<TorrentSettings>()
    val controller = InstanceSettingsController(
      api = SettingsKetchApi(),
      local = AppSettingsController(store),
      scope = this,
      applyTorrent = { applied += it },
    )
    val seeding = TorrentSettings(upload = TorrentUploadMode.Seed)
    val capped = seeding.copy(uploadLimit = SpeedLimit.kbps(512))

    controller.updateTorrent(seeding)
    advanceUntilIdle()
    controller.updateTorrent(capped)
    advanceUntilIdle()

    assertEquals(capped, store.load().torrent)
    assertEquals(capped, controller.torrent)
    assertEquals(listOf(seeding, capped), applied)
  }

  @Test
  fun updateTorrent_uploadWifiOnlyOff_isSavedAndApplied() = runTest {
    val store = RecordingConfigStore()
    val applied = mutableListOf<TorrentSettings>()
    val controller = InstanceSettingsController(
      api = SettingsKetchApi(),
      local = AppSettingsController(store),
      scope = this,
      applyTorrent = { applied += it },
    )
    val anyNetwork = TorrentSettings(uploadWifiOnly = false)

    controller.updateTorrent(anyNetwork)
    advanceUntilIdle()

    assertFalse(store.load().torrent.uploadWifiOnly)
    assertEquals(anyNetwork, controller.torrent)
    assertEquals(listOf(anyNetwork), applied)
  }

  @Test
  fun `a torrent change that fails to apply stays saved and is reported`() = runTest {
    val store = RecordingConfigStore()
    val controller = InstanceSettingsController(
      api = SettingsKetchApi(),
      local = AppSettingsController(store),
      scope = this,
      applyTorrent = { throw IllegalStateException("Engine closed") },
    )
    val edited = TorrentSettings(trackers = listOf("https://tracker.example/announce"))

    controller.updateTorrent(edited)
    advanceUntilIdle()

    assertEquals(edited, store.load().torrent)
    assertEquals("Engine closed", controller.torrentError.load())
  }

  @Test
  fun loadTorrent_remoteWithoutTorrentControls_isUnsupported() = runTest {
    val applied = mutableListOf<TorrentSettings>()
    val controller = InstanceSettingsController(
      api = SettingsKetchApi(),
      local = null,
      scope = this,
      applyTorrent = { applied += it },
    )

    controller.loadTorrent()
    advanceUntilIdle()
    controller.updateTorrent(TorrentSettings(trackers = listOf("https://tracker.example/a")))
    advanceUntilIdle()

    assertTrue(controller.torrentUnsupported)
    assertNull(controller.torrent)
    assertEquals(emptyList(), applied)
  }

  @Test
  fun loadTorrent_olderRemote_isUnsupportedWithoutAsking() = runTest {
    val torrents = SettingsTorrentController(names = setOf("inspect", "reachability"))
    val controller = InstanceSettingsController(
      api = SettingsKetchApi(torrents = torrents),
      local = null,
      scope = this,
    )

    controller.loadTorrent()
    advanceUntilIdle()

    assertTrue(controller.torrentUnsupported)
    assertNull(controller.torrent)
  }

  @Test
  fun updateTorrent_remote_sendsOnlyTheEditAndShowsWhatItSaved() = runTest {
    val store = RecordingConfigStore()
    val remote = TorrentEngineSettings(
      upload = TorrentUploadSetting.SEED,
      trackerListUrls = listOf("https://lists.example/best.txt"),
    )
    val torrents = SettingsTorrentController(settings = remote)
    val applied = mutableListOf<TorrentSettings>()
    val controller = InstanceSettingsController(
      api = SettingsKetchApi(torrents = torrents),
      local = null,
      scope = this,
      applyTorrent = { applied += it },
    )

    assertNull(controller.torrent)
    controller.loadTorrent()
    advanceUntilIdle()
    val shown = assertNotNull(controller.torrent)
    assertEquals(TorrentUploadMode.Seed, shown.upload)
    assertEquals(listOf("https://lists.example/best.txt"), shown.trackerListAddresses)
    assertTrue(controller.localDiscoverySupported)

    controller.updateTorrent(shown.copy(utp = false, encryption = TorrentEncryptionMode.Off))
    advanceUntilIdle()

    assertEquals(
      listOf(TorrentSettingsChange(encryption = TorrentEncryptionSetting.OFF, utp = false)),
      torrents.changes,
    )
    assertEquals(false, controller.torrent?.utp)
    // The remote device saves them; neither this app's file nor its engine changes.
    assertEquals(TorrentSettings(), store.load().torrent)
    assertEquals(emptyList(), applied)
  }

  @Test
  fun updateTorrent_editQueuedWhileOneIsSent_keepsWhatAnotherDeviceChanged() = runTest {
    val gate = CompletableDeferred<Unit>()
    val torrents = SettingsTorrentController().apply { this.gate = gate }
    val controller = InstanceSettingsController(
      api = SettingsKetchApi(torrents = torrents),
      local = null,
      scope = this,
    )
    controller.loadTorrent()
    advanceUntilIdle()

    controller.updateTorrent(assertNotNull(controller.torrent).copy(utp = false))
    runCurrent()
    // Another device starts seeding while the first edit is on its way, and this page edits on.
    controller.updateTorrent(assertNotNull(controller.torrent).copy(portMapping = false))
    torrents.settings = torrents.settings.copy(upload = TorrentUploadSetting.SEED)
    gate.complete(Unit)
    advanceUntilIdle()

    assertEquals(
      listOf(TorrentSettingsChange(utp = false), TorrentSettingsChange(portMapping = false)),
      torrents.changes,
    )
    assertEquals(TorrentUploadSetting.SEED, torrents.settings.upload)
    val shown = assertNotNull(controller.torrent)
    assertEquals(TorrentUploadMode.Seed, shown.upload)
    assertFalse(shown.utp)
    assertFalse(shown.portMapping)
  }

  @Test
  fun updateTorrent_remoteRefuses_showsWhatItConfirmed() = runTest {
    val torrents = SettingsTorrentController().apply { refuse = true }
    val controller = InstanceSettingsController(
      api = SettingsKetchApi(torrents = torrents),
      local = null,
      scope = this,
    )
    controller.loadTorrent()
    advanceUntilIdle()

    controller.updateTorrent(assertNotNull(controller.torrent).copy(portMapping = false))
    advanceUntilIdle()

    assertNotNull(controller.torrentError)
    assertEquals(true, controller.torrent?.portMapping)
  }

  @Test
  fun followEngineTorrent_changedByAnotherDevice_isSavedKeepingTheHosts() = runTest {
    val saved = TorrentSettings(listenPort = 16881, uploadWifiOnly = false)
    val store = RecordingConfigStore(KetchConfig(torrent = saved))
    val engine = MutableStateFlow(saved.engineSettings)
    val applied = mutableListOf<TorrentSettings>()
    val controller = InstanceSettingsController(
      api = SettingsKetchApi(),
      local = AppSettingsController(store),
      scope = this,
      applyTorrent = {
        applied += it
        engine.value = it.engineSettings
      },
      engineTorrent = engine,
    )
    val following = launch { controller.followEngineTorrent() }
    advanceUntilIdle()
    assertEquals(0, store.saves)

    engine.value = saved.engineSettings.copy(upload = TorrentUploadSetting.SEED, utp = false)
    advanceUntilIdle()

    val adopted = store.load().torrent
    assertEquals(engine.value, adopted.engineSettings)
    assertEquals(16881, adopted.listenPort)
    assertEquals(false, adopted.uploadWifiOnly)
    assertEquals(adopted, controller.torrent)

    // Putting back what this page last applied reaches the engine, as it no longer runs it.
    controller.updateTorrent(saved)
    advanceUntilIdle()
    assertEquals(listOf(saved), applied)
    assertEquals(saved, store.load().torrent)
    following.cancel()
  }

  @Test
  fun `remote settings come from the instance and stay off the local file`() = runTest {
    val store = RecordingConfigStore()
    val remoteConfig = DownloadConfig(maxConnectionsPerDownload = 12)
    val api = SettingsKetchApi(config = remoteConfig)
    val controller = InstanceSettingsController(api, local = null, scope = this)

    assertNull(controller.download)
    controller.loadDownload()
    advanceUntilIdle()
    assertEquals(remoteConfig, controller.download)

    controller.updateDownload(remoteConfig.copy(retryCount = 0))
    advanceUntilIdle()
    assertEquals(0, api.config.retryCount)
    assertEquals(DownloadConfig(), store.load().download)
  }

  @Test
  fun `rapid changes leave the instance on the last one`() = runTest {
    val api = SettingsKetchApi()
    val controller = InstanceSettingsController(api, local = null, scope = this)
    val first = DownloadConfig(maxConcurrentDownloads = 1)
    val second = DownloadConfig(maxConcurrentDownloads = 2)
    val third = DownloadConfig(maxConcurrentDownloads = 3)
    api.gate = CompletableDeferred()

    controller.updateDownload(first)
    advanceUntilIdle() // first call is now in flight
    controller.updateDownload(second)
    controller.updateDownload(third)
    api.gate?.complete(Unit)
    advanceUntilIdle()

    // The superseded value is skipped rather than sent late.
    assertEquals(listOf(first, third), api.applied)
    assertEquals(third, controller.download)
  }

  @Test
  fun `a rejected change is reported`() = runTest {
    val api = SettingsKetchApi().apply { failUpdates = true }
    val controller = InstanceSettingsController(api, local = null, scope = this)

    controller.updateDownload(DownloadConfig(retryCount = 1))
    advanceUntilIdle()

    assertEquals("Server said no", controller.downloadError.load())
  }

  @Test
  fun `a network selection shows at once and rolls back when rejected`() = runTest {
    val available = listOf(
      NetworkInterfaceInfo("en0", "Wi-Fi", listOf("192.168.1.2")),
      NetworkInterfaceInfo("en1", "Ethernet", listOf("10.0.0.2")),
    )
    val api = SettingsKetchApi(networks = NetworkInterfaces(supported = true, available))
    val controller = InstanceSettingsController(api, local = null, scope = this)
    controller.loadNetworks()
    advanceUntilIdle()

    controller.selectNetworks(listOf("en1"))
    advanceUntilIdle()
    assertEquals(listOf("en1"), controller.networks?.config?.interfaceIds)

    api.failUpdates = true
    controller.selectNetworks(listOf("en1", "en0"))
    assertEquals(listOf("en1", "en0"), controller.networks?.config?.interfaceIds)
    advanceUntilIdle()
    assertEquals(listOf("en1"), controller.networks?.config?.interfaceIds)
    assertNotNull(controller.networkError)
  }

  @Test
  fun `failed selections fall back to what the instance last confirmed`() = runTest {
    val available = listOf(
      NetworkInterfaceInfo("a", "Wi-Fi", listOf("192.168.1.2")),
      NetworkInterfaceInfo("b", "Ethernet", listOf("10.0.0.2")),
    )
    val api = SettingsKetchApi(networks = NetworkInterfaces(supported = true, available))
    val controller = InstanceSettingsController(api, local = null, scope = this)
    controller.loadNetworks()
    advanceUntilIdle()
    api.failUpdates = true
    api.gate = CompletableDeferred()

    controller.selectNetworks(listOf("a"))
    advanceUntilIdle() // "a" is in flight
    controller.selectNetworks(listOf("a", "b"))
    controller.selectNetworks(listOf("b"))
    api.gate?.complete(Unit)
    advanceUntilIdle()

    // Only the newest queued selection is sent after the one in flight.
    assertEquals(listOf(listOf("a"), listOf("b")), api.networkRequests)
    // Neither was accepted, so nothing is ticked.
    assertEquals(emptyList(), controller.networks?.config?.interfaceIds)
    assertNotNull(controller.networkError)
  }

  @Test
  fun loadDownload_embeddedInstance_showsWhatTheInstanceUses() = runTest {
    val live = DownloadConfig(speedLimit = SpeedLimit.mbps(2))
    val controller = InstanceSettingsController(
      api = SettingsKetchApi(config = live),
      local = AppSettingsController(RecordingConfigStore()),
      scope = this,
    )

    controller.loadDownload()
    advanceUntilIdle()

    assertEquals(live, controller.download)
  }

  @Test
  fun updateDownload_accepted_readsBackTheInstanceConfig() = runTest {
    val api = SettingsKetchApi().apply { accept = { it.copy(maxConcurrentDownloads = 10) } }
    val controller = InstanceSettingsController(api, local = null, scope = this)

    controller.updateDownload(DownloadConfig(maxConcurrentDownloads = 99))
    advanceUntilIdle()

    assertEquals(10, controller.download?.maxConcurrentDownloads)
  }

  @Test
  fun loadDownload_editWaitingToBeSent_keepsTheEdit() = runTest {
    val api = SettingsKetchApi()
    val controller = InstanceSettingsController(api, local = null, scope = this)
    val first = DownloadConfig(maxConcurrentDownloads = 1)
    val second = DownloadConfig(maxConcurrentDownloads = 2)
    api.gate = CompletableDeferred()

    controller.updateDownload(first)
    advanceUntilIdle() // first is in flight
    controller.updateDownload(second)
    controller.loadDownload()
    api.gate?.complete(Unit)
    advanceUntilIdle()

    assertEquals(second, controller.download)
    assertEquals(listOf(first, second), api.applied)
  }

  @Test
  fun acceptShared_savesTheStandingCapAndShowsTheSettings() = runTest {
    val store = RecordingConfigStore()
    val controller = InstanceSettingsController(
      api = SettingsKetchApi(),
      local = AppSettingsController(store),
      scope = this,
      savedSpeedLimit = { SpeedLimit.Unlimited },
    )
    // A phone changes the queue while the slow lane holds this device's limit.
    val shared = DownloadConfig(maxConcurrentDownloads = 2, speedLimit = SpeedLimit.kbps(200))

    controller.acceptShared(shared)

    assertEquals(shared.copy(speedLimit = SpeedLimit.Unlimited), store.load().download)
    assertEquals(shared, controller.download)
  }

  @Test
  fun acceptShared_editTheInstanceTookMeanwhile_keepsTheEdit() = runTest {
    val store = RecordingConfigStore()
    val api = SettingsKetchApi()
    val controller = InstanceSettingsController(api, AppSettingsController(store), this)
    val edit = DownloadConfig(maxConcurrentDownloads = 3)
    api.gate = CompletableDeferred()
    controller.updateDownload(edit)
    advanceUntilIdle() // the edit is in flight

    launch { controller.acceptShared(DownloadConfig(maxConcurrentDownloads = 2)) }
    advanceUntilIdle()
    api.gate?.complete(Unit)
    advanceUntilIdle()

    assertEquals(edit, store.load().download)
    assertEquals(edit, controller.download)
  }
}
