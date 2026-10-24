package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.torrent.api.TorrentCapability
import com.linroid.ketch.torrent.api.TorrentCommandError
import com.linroid.ketch.torrent.api.TorrentCommandException
import com.linroid.ketch.torrent.api.TorrentController
import com.linroid.ketch.torrent.api.TorrentEncryptionSetting
import com.linroid.ketch.torrent.api.TorrentEngineSettings
import com.linroid.ketch.torrent.api.TorrentSettingsChange
import com.linroid.ketch.torrent.api.TorrentUploadSetting
import com.linroid.ketch.torrent.lsd.lsdSupported
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/** The settings a torrent source runs with, as hosts and its controller read and change them. */
class TorrentSourceSettingsTest {
  private val custom = TorrentEngineSettings(
    upload = TorrentUploadSetting.SEED,
    uploadLimit = SpeedLimit.kbps(64),
    encryption = TorrentEncryptionSetting.REQUIRED,
    utp = false,
    portMapping = false,
    localDiscovery = false,
    trackers = listOf("udp://tracker.example:1337/announce"),
    trackerList = false,
    trackerListUrls = listOf("https://lists.example/best.txt"),
  )

  @Test
  fun settings_followTheConfig_keepingListsSwitchedOff() {
    val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false).withSettings(custom))
    try {
      assertEquals(custom, source.settings.value)
      assertEquals(TorrentUploadPolicy.SEED_AFTER_COMPLETION, source.currentUploadPolicy)
      // Switched off, the lists stay known without being subscribed to.
      assertTrue(source.trackerLists.value.isEmpty())
    } finally {
      source.close()
    }
  }

  @Test
  fun setters_showInTheSettings() = runTest {
    val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false))
    try {
      source.setUploadPolicy(TorrentUploadPolicy.SEED_AFTER_COMPLETION)
      source.setUploadRateLimit(2048)
      source.setUtpEnabled(false)
      source.setTrackerLists(emptyList())

      assertEquals(
        TorrentEngineSettings(
          upload = TorrentUploadSetting.SEED,
          uploadLimit = SpeedLimit.of(2048),
          utp = false,
          trackerList = true,
          trackerListUrls = emptyList(),
        ),
        source.settings.value,
      )
    } finally {
      source.close()
    }
  }

  @Test
  fun applySettings_publishesOnceForEveryChange() = runTest {
    val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false))
    val seen = mutableListOf<TorrentEngineSettings>()
    val collector = backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) {
      source.settings.toList(seen)
    }
    try {
      val wanted = custom.copy(trackerList = true, trackerListUrls = emptyList())
      source.applySettings(wanted)
      // Hosts that save what they see never see half a change.
      assertEquals(listOf(TorrentEngineSettings(upload = TorrentUploadSetting.OFF), wanted), seen)
      assertEquals(TorrentUploadPolicy.SEED_AFTER_COMPLETION, source.currentUploadPolicy)
    } finally {
      collector.cancel()
      source.close()
    }
  }

  @Test
  fun controller_updateSettings_appliesTheChangeAndKeepsTheRest() = runTest {
    val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false).withSettings(custom))
    val ketch = ketchWith(source)
    try {
      val controller = assertNotNull(ketch.controller(TorrentController))
      val capabilities = controller.capabilities()
      assertTrue(capabilities.supports(TorrentCapability.SETTINGS))
      assertEquals(lsdSupported, capabilities.supports(TorrentCapability.LOCAL_DISCOVERY))
      assertEquals(custom, controller.settings())

      val updated = controller.updateSettings(
        TorrentSettingsChange(upload = TorrentUploadSetting.OFF, trackerList = true),
      )

      assertEquals(custom.copy(upload = TorrentUploadSetting.OFF, trackerList = true), updated)
      assertEquals(updated, source.settings.value)
      assertEquals(TorrentUploadPolicy.DISABLED, source.currentUploadPolicy)
      // Seeding follows the upload setting a client changed.
      assertFalse(controller.capabilities().supports(TorrentCapability.SEEDING))
    } finally {
      ketch.close()
    }
  }

  @Test
  fun controller_updateSettings_refusesMalformedTrackers() = runTest {
    val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false))
    val ketch = ketchWith(source)
    try {
      val controller = assertNotNull(ketch.controller(TorrentController))
      val before = source.settings.value
      val changes = listOf(
        TorrentSettingsChange(trackers = List(1025) { "udp://t$it.example:1/announce" }),
        TorrentSettingsChange(trackers = listOf("")),
        TorrentSettingsChange(trackerListUrls = listOf("https://lists.example/a b.txt")),
        TorrentSettingsChange(trackerListUrls = List(65) { "https://lists.example/$it.txt" }),
      )
      for (change in changes) {
        val failure = assertFailsWith<TorrentCommandException> { controller.updateSettings(change) }
        assertEquals(TorrentCommandError.INVALID_INPUT, failure.error)
      }
      assertEquals(before, source.settings.value)
    } finally {
      ketch.close()
    }
  }

  // A default folder of its own: the platform's needs an Android context.
  private fun ketchWith(source: TorrentDownloadSource) = Ketch(
    UnusedHttp,
    config = DownloadConfig(defaultDirectory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString()),
    additionalSources = listOf(source),
  )

  private object UnusedHttp : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("unused")
    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ): Unit = error("unused")
    override fun close() = Unit
  }
}
