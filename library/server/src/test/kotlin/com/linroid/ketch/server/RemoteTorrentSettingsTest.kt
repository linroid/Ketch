package com.linroid.ketch.server

import com.linroid.ketch.api.ControlKey
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.remote.RemoteKetch
import com.linroid.ketch.torrent.api.TorrentCapabilities
import com.linroid.ketch.torrent.api.TorrentCapability
import com.linroid.ketch.torrent.api.TorrentCommandError
import com.linroid.ketch.torrent.api.TorrentCommandException
import com.linroid.ketch.torrent.api.TorrentController
import com.linroid.ketch.torrent.api.TorrentEncryptionSetting
import com.linroid.ketch.torrent.api.TorrentEngineSettings
import com.linroid.ketch.torrent.api.TorrentSettingsChange
import com.linroid.ketch.torrent.api.TorrentSnapshot
import com.linroid.ketch.torrent.api.TorrentUploadSetting
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** A server's torrent settings, read and changed by a remote client. */
class RemoteTorrentSettingsTest {

  /** Keeps settings like an engine, with the capabilities it is given. */
  private class SettingsController(names: Set<String>) : TorrentController {
    private val capabilities = TorrentCapabilities(names = names)
    val changes = mutableListOf<TorrentSettingsChange>()
    var reads = 0
    var settings = TorrentEngineSettings(
      upload = TorrentUploadSetting.SEED,
      trackerListUrls = listOf("https://lists.example/best.txt"),
    )

    override suspend fun capabilities() = capabilities

    override suspend fun snapshot(taskId: String): TorrentSnapshot? = null

    override suspend fun settings(): TorrentEngineSettings {
      reads++
      return settings
    }

    override suspend fun updateSettings(change: TorrentSettingsChange): TorrentEngineSettings {
      changes += change
      settings = change.applyTo(settings)
      return settings
    }
  }

  private class ControlledKetch(
    delegate: KetchApi,
    private val torrents: TorrentController,
  ) : KetchApi by delegate {
    @Suppress("UNCHECKED_CAST")
    override fun <T : Any> controller(key: ControlKey<T>): T? =
      if (key === TorrentController.Key) torrents as T else null
  }

  private suspend fun withServer(
    controller: SettingsController,
    block: suspend (TorrentController) -> Unit,
  ) = withContext(Dispatchers.Default) {
    withTimeout(30_000) {
      val ketch = createTestKetch()
      val server = KetchServer(ControlledKetch(ketch, controller), port = 0, mdnsEnabled = false)
      var remote: RemoteKetch? = null
      try {
        server.start(wait = false)
        val client = RemoteKetch("127.0.0.1", server.port()).also { remote = it }
        block(assertNotNull(client.controller(TorrentController)))
      } finally {
        remote?.close()
        server.stop()
        ketch.close()
      }
    }
  }

  @Test
  fun remote_changesOnlyWhatItSets_andReadsTheResult() = runTest {
    val controller = SettingsController(setOf("inspect", "settings", "local-discovery"))
    withServer(controller) { torrents ->
      val capabilities = torrents.capabilities()
      assertTrue(capabilities.supports(TorrentCapability.SETTINGS))
      assertTrue(capabilities.supports(TorrentCapability.LOCAL_DISCOVERY))
      assertEquals(controller.settings, torrents.settings())

      val change = TorrentSettingsChange(
        uploadLimit = SpeedLimit.kbps(512),
        encryption = TorrentEncryptionSetting.REQUIRED,
        trackerList = false,
      )
      val updated = torrents.updateSettings(change)

      // Fields the client left out reach the server as unchanged, never as defaults.
      assertEquals(listOf(change), controller.changes)
      assertEquals(TorrentUploadSetting.SEED, updated.upload)
      assertEquals(listOf("https://lists.example/best.txt"), updated.trackerListUrls)
      assertEquals(controller.settings, updated)
    }
  }

  @Test
  fun remote_olderServer_failsClosedWithoutAsking() = runTest {
    val controller = SettingsController(setOf("inspect", "reachability"))
    withServer(controller) { torrents ->
      assertFalse(torrents.capabilities().supports(TorrentCapability.SETTINGS))
      val read = assertFailsWith<TorrentCommandException> { torrents.settings() }
      val change = assertFailsWith<TorrentCommandException> {
        torrents.updateSettings(TorrentSettingsChange(utp = false))
      }

      assertEquals(TorrentCommandError.UNSUPPORTED, read.error)
      assertEquals(TorrentCommandError.UNSUPPORTED, change.error)
      assertEquals(0, controller.reads)
      assertTrue(controller.changes.isEmpty())
    }
  }
}
