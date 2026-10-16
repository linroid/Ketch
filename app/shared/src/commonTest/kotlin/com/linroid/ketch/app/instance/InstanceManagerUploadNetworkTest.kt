package com.linroid.ketch.app.instance

import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.TorrentSettings
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@OptIn(ExperimentalCoroutinesApi::class)
class InstanceManagerUploadNetworkTest {
  private val network = MutableStateFlow(false)
  private val pauses = mutableListOf<Boolean>()
  private val applied = mutableListOf<TorrentSettings>()

  private fun factory(
    embedded: Boolean = true,
    followsNetwork: Boolean = true,
  ) = InstanceFactory(
    embeddedFactory = if (embedded) ({ FakeKetchApi() }) else null,
    applyTorrentSettings = { applied += it },
    uploadNetwork = network.takeIf { followsNetwork },
    pauseUploads = { pauses += it },
  )

  private fun TestScope.manager(
    factory: InstanceFactory = factory(),
    torrent: TorrentSettings = TorrentSettings(),
  ): InstanceManager {
    val manager = InstanceManager(
      factory = factory,
      configStore = RecordingConfigStore(KetchConfig(torrent = torrent)),
      context = backgroundScope.coroutineContext,
    )
    runCurrent()
    return manager
  }

  private fun TestScope.settle() {
    advanceTimeBy(UNPAUSE_SETTLE * 2)
    runCurrent()
  }

  @Test
  fun init_savedWifiOnlyOnAMeteredNetwork_pausesUploads() = runTest {
    val manager = manager()

    assertTrue(manager.followsUploadNetwork)
    assertEquals(listOf(true), pauses)
    manager.close()
  }

  @Test
  fun init_savedWifiOnlyOff_neverPausesUploads() = runTest {
    val manager = manager(torrent = TorrentSettings(uploadWifiOnly = false))
    settle()

    assertEquals(listOf(false), pauses)
    manager.close()
  }

  @Test
  fun applyTorrentSettings_wifiOnlyTurnedOff_resumesUploads() = runTest {
    val manager = manager()
    val edited = TorrentSettings(uploadWifiOnly = false)

    manager.applyTorrentSettings(edited)
    settle()

    assertEquals(listOf(true, false), pauses)
    assertEquals(listOf(edited), applied)
    manager.close()
  }

  @Test
  fun close_stopsFollowingTheNetwork() = runTest {
    val manager = manager()

    manager.close()
    network.value = true
    settle()

    assertEquals(listOf(true), pauses)
  }

  @Test
  fun factoryWithoutUploadNetwork_neverPausesUploads() = runTest {
    val manager = manager(factory(followsNetwork = false))
    settle()

    assertFalse(manager.followsUploadNetwork)
    assertEquals(emptyList(), pauses)
    manager.close()
  }

  @Test
  fun factoryWithoutEmbeddedDevice_neverPausesUploads() = runTest {
    val manager = manager(factory(embedded = false))
    settle()

    assertFalse(manager.followsUploadNetwork)
    assertEquals(emptyList(), pauses)
    manager.close()
  }
}
