package com.linroid.ketch.cli

import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.TorrentSettings
import com.linroid.ketch.config.TorrentUploadMode
import com.linroid.ketch.torrent.api.TorrentUploadSetting
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class TorrentSettingsSaverTest {
  /** A config file in memory, which [unreadable] makes fail to load. */
  private class MemoryStore(var config: KetchConfig) : ConfigStore {
    var unreadable = false
    var saves = 0

    override fun load(): KetchConfig {
      check(!unreadable) { "Unexpected character" }
      return config
    }

    override fun save(config: KetchConfig) {
      this.config = config
      saves++
    }
  }

  // Saves run as each change is made: the saver follows the settings without a dispatcher of its
  // own. Not runTest, which reports exceptions other tests of this process leaked, such as
  // ServeDaemonTest's server that cannot bind.
  private val inPlace = CoroutineScope(Dispatchers.Unconfined)

  @AfterTest
  fun stop() {
    inPlace.cancel()
  }

  @Test
  fun change_savesOnlyWhatChanged_overWhatTheFileSays() {
    val file = TorrentSettings(upload = TorrentUploadMode.Off, listenPort = 16881)
    val store = MemoryStore(KetchConfig(torrent = file))
    // Started seeding, as `--torrent-upload seed` overrides the file.
    val started = file.engineSettings.copy(upload = TorrentUploadSetting.SEED)
    val engine = MutableStateFlow(started)
    val saver = TorrentSettingsSaver(engine, started, store).start(inPlace)
    try {
      engine.value = started.copy(utp = false)

      assertEquals(file.copy(utp = false), store.config.torrent)
    } finally {
      saver.close()
    }
  }

  @Test
  fun unreadableFile_isLeftAlone_andTheChangeSavedWithTheNext() {
    val store = MemoryStore(KetchConfig()).apply { unreadable = true }
    val started = TorrentSettings().engineSettings
    val engine = MutableStateFlow(started)
    val saver = TorrentSettingsSaver(engine, started, store).start(inPlace)
    try {
      engine.value = started.copy(portMapping = false)
      assertEquals(0, store.saves)

      store.unreadable = false
      engine.value = started.copy(portMapping = false, localDiscovery = false)

      assertEquals(1, store.saves)
      assertFalse(store.config.torrent.portMapping)
      assertFalse(store.config.torrent.localDiscovery)
    } finally {
      saver.close()
    }
  }
}
