package com.linroid.ketch.engine

import com.linroid.ketch.api.NetworkInterfaceConfig
import com.linroid.ketch.api.NetworkInterfaceInfo
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.ConfigurableNetworkHttpEngine
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.NetworkInterfaceProvider
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigurableNetworkHttpEngineTest {
  @Test
  fun ketchApi_discoversSelectsDispatchesAndRestoresDefault() = runTest {
    val provider = Provider()
    val engine = ConfigurableNetworkHttpEngine(provider)
    val ketch = Ketch(engine)
    try {
      assertTrue(ketch.networkInterfaces().supported)
      assertEquals(listOf("wifi", "ethernet"), ketch.networkInterfaces().available.map { it.id })
      val selected = ketch.updateNetworkInterfaces(config("wifi", "ethernet"))
      assertEquals(listOf("wifi", "ethernet"), selected.config.interfaceIds)
      repeat(4) { engine.download("url", null) {} }
      assertEquals(listOf("wifi", "ethernet", "wifi", "ethernet"), provider.calls)
      assertEquals(1, provider.engines.first().closed)

      ketch.updateNetworkInterfaces(NetworkInterfaceConfig())
      engine.head("url")
      assertEquals("default", provider.calls.last())
      assertTrue(ketch.networkInterfaces().config.interfaceIds.isEmpty())
      assertTrue(provider.engines.dropLast(1).all { it.closed == 1 })
    } finally {
      ketch.close()
    }
    assertTrue(provider.engines.all { it.closed == 1 })
  }

  @Test
  fun updates_leaveInFlightRequestsOnOldEngineUntilTheyFinish() = runTest {
    val provider = Provider()
    val engine = ConfigurableNetworkHttpEngine(provider)
    val started = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val original = provider.engines.single()
    original.onDownload = {
      started.complete(Unit)
      release.await()
    }
    val request = async { engine.download("url", null) {} }
    started.await()
    engine.updateNetworkInterfaces(config("wifi"))
    engine.download("url", null) {}
    assertEquals(listOf("default", "wifi"), provider.calls)
    assertEquals(0, original.closed)
    release.complete(Unit)
    request.await()
    assertEquals(1, original.closed)
    engine.close()
    assertTrue(provider.engines.all { it.closed == 1 })
  }

  @Test
  fun cancellation_releasesRetiredEngineAndCloseRejectsNewRequests() = runTest {
    val provider = Provider()
    val engine = ConfigurableNetworkHttpEngine(provider)
    val started = CompletableDeferred<Unit>()
    provider.engines.single().onDownload = {
      started.complete(Unit)
      CompletableDeferred<Unit>().await()
    }
    val request = async { engine.download("url", null) {} }
    started.await()
    engine.updateNetworkInterfaces(config("wifi"))
    request.cancelAndJoin()
    assertEquals(1, provider.engines.first().closed)
    engine.close()
    engine.close()
    assertTrue(provider.engines.all { it.closed == 1 })
    assertFailsWith<IllegalStateException> { engine.download("url", null) {} }
    assertFailsWith<IllegalStateException> { engine.updateNetworkInterfaces(config("wifi")) }
  }

  @Test
  fun invalidOrFailedSelection_preservesPreviousEngineAndClosesPartialReplacement() = runTest {
    val provider = Provider()
    val engine = ConfigurableNetworkHttpEngine(provider)
    assertFailsWith<IllegalArgumentException> {
      engine.updateNetworkInterfaces(config("wifi", "missing"))
    }
    assertEquals(1, provider.engines.size)
    provider.failOn = "ethernet"
    assertFailsWith<IllegalStateException> {
      engine.updateNetworkInterfaces(config("wifi", "ethernet"))
    }
    assertEquals(1, provider.engines.last().closed)
    engine.download("url", null) {}
    assertEquals(listOf("default"), provider.calls)
    assertTrue(engine.networkInterfaces().config.interfaceIds.isEmpty())
    engine.close()
  }

  @Test
  fun closeDuringDiscovery_cannotInstallReplacement() = runTest {
    val provider = Provider()
    val engine = ConfigurableNetworkHttpEngine(provider)
    provider.onDiscover = { engine.close() }
    assertFailsWith<IllegalStateException> { engine.updateNetworkInterfaces(config("wifi")) }
    assertTrue(provider.engines.all { it.closed == 1 })
  }

  @Test
  fun disappearedSelection_remainsVisibleAndCallerMutationsDoNotChangeIt() = runTest {
    val provider = Provider()
    val engine = ConfigurableNetworkHttpEngine(provider)
    val ids = mutableListOf("wifi", "ethernet")
    engine.updateNetworkInterfaces(NetworkInterfaceConfig(ids))
    ids.clear()
    provider.available = emptyList()
    val state = engine.networkInterfaces()
    assertTrue(state.available.isEmpty())
    assertEquals(listOf("wifi", "ethernet"), state.config.interfaceIds)
    assertFailsWith<IllegalArgumentException> {
      engine.updateNetworkInterfaces(config("wifi"))
    }
    engine.close()
  }

  @Test
  fun plainEngine_reportsUnsupported() = runTest {
    val ketch = Ketch(RecordingEngine("plain", mutableListOf()))
    try {
      assertFalse(ketch.networkInterfaces().supported)
      assertFailsWith<UnsupportedOperationException> {
        ketch.updateNetworkInterfaces(NetworkInterfaceConfig())
      }
    } finally {
      ketch.close()
    }
  }

  @Test
  fun config_rejectsBlankAndDuplicateIds() {
    assertFailsWith<IllegalArgumentException> { config(" ") }
    assertFailsWith<IllegalArgumentException> { config("wifi", "wifi") }
  }

  private fun config(vararg ids: String): NetworkInterfaceConfig =
    NetworkInterfaceConfig(ids.toList())

  private class Provider : NetworkInterfaceProvider {
    val calls = mutableListOf<String>()
    val engines = mutableListOf<RecordingEngine>()
    var available = listOf(
      NetworkInterfaceInfo("wifi", "Wi-Fi", listOf("192.0.2.1")),
      NetworkInterfaceInfo("ethernet", "Ethernet", listOf("192.0.2.2"))
    )
    var failOn: String? = null
    var onDiscover: suspend () -> Unit = {}

    override suspend fun availableInterfaces(): List<NetworkInterfaceInfo> {
      onDiscover()
      return available
    }

    override fun createDefaultEngine(): HttpEngine = create("default")
    override fun createEngine(networkInterface: NetworkInterfaceInfo): HttpEngine =
      create(networkInterface.id)

    private fun create(id: String): RecordingEngine {
      check(id != failOn) { "Interface disappeared" }
      return RecordingEngine(id, calls).also { engines.add(it) }
    }
  }

  private class RecordingEngine(val id: String, val calls: MutableList<String>) : HttpEngine {
    var closed = 0
    var onDownload: suspend () -> Unit = {}

    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo {
      calls.add(id)
      return ServerInfo(10, true, null, null)
    }

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      calls.add(id)
      onDownload()
      onData(byteArrayOf(1))
    }

    override fun close() {
      closed++
    }
  }
}
