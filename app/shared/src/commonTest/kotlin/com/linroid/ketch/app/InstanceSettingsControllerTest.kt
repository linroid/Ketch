package com.linroid.ketch.app

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.api.NetworkInterfaces
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SystemInfo
import com.linroid.ketch.app.state.AppSettingsController
import com.linroid.ketch.app.state.InstanceSettingsController
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/** Instance that records the settings pushed to it. */
private class SettingsKetchApi(
  var config: DownloadConfig = DownloadConfig(),
  var networks: NetworkInterfaces = NetworkInterfaces(),
) : KetchApi {
  override val backendLabel = "Settings"
  override val tasks = MutableStateFlow(emptyList<DownloadTask>())
  val applied = mutableListOf<DownloadConfig>()
  val networkRequests = mutableListOf<List<String>>()
  var gate: CompletableDeferred<Unit>? = null
  var failUpdates = false

  override suspend fun download(request: DownloadRequest): DownloadTask =
    throw UnsupportedOperationException()

  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
    throw UnsupportedOperationException()

  override suspend fun status(): KetchStatus = KetchStatus(
    name = "remote",
    version = "test",
    revision = "test",
    uptime = 0,
    config = config,
    system = SystemInfo(
      os = "test", arch = "test", separator = "/", javaVersion = "N/A",
      availableProcessors = 1, maxMemory = 0, totalMemory = 0, freeMemory = 0,
      downloadDirectory = "/downloads", totalSpace = 0, freeSpace = 0, usableSpace = 0,
    ),
  )

  override suspend fun updateConfig(config: DownloadConfig) {
    gate?.await()
    if (failUpdates) throw IllegalStateException("Server said no")
    applied += config
    this.config = config
  }

  override suspend fun networkInterfaces(): NetworkInterfaces = networks

  override suspend fun updateNetworkInterfaces(config: NetworkInterfaceConfig): NetworkInterfaces {
    gate?.await()
    networkRequests += config.interfaceIds
    if (failUpdates) throw IllegalArgumentException("Unknown interface")
    networks = networks.copy(config = config)
    return networks
  }

  override suspend fun start() {}
  override fun close() {}
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

    assertEquals("Server said no", controller.downloadError)
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
}
