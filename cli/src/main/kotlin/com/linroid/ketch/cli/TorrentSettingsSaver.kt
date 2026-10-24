package com.linroid.ketch.cli

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.torrent.api.TorrentEngineSettings
import com.linroid.ketch.torrent.api.TorrentSettingsChange
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch

/**
 * Saves to the `[torrent]` table of [store] the torrent settings that clients of this process's
 * server change ([com.linroid.ketch.torrent.api.TorrentController.updateSettings]), so they
 * outlive a restart.
 *
 * Each change is written over the file as it is then, setting only the settings that changed
 * since [started], the ones the engine started with: a setting an option or environment variable
 * gave, such as `--torrent-upload`, stays as the file has it until a client changes it, and a
 * restart with the option keeps overriding it. A file that cannot be read is left as it is.
 */
internal class TorrentSettingsSaver(
  private val settings: StateFlow<TorrentEngineSettings>,
  private val started: TorrentEngineSettings,
  private val store: ConfigStore,
) : AutoCloseable {
  private val log = KetchLogger("ConfigStore")
  private var following: Job? = null

  /** Starts following the settings in [scope]; [close] stops it. */
  fun start(
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO),
  ): TorrentSettingsSaver = apply {
    following = scope.launch {
      var saved = started
      settings.collect { current ->
        val change = TorrentSettingsChange.between(saved, current)
        if (change.isEmpty) return@collect
        // A change that could not be saved is tried again with the next one.
        if (save(change)) saved = current
      }
    }
  }

  /** Writes [change] over the file's settings; returns whether it did. */
  private fun save(change: TorrentSettingsChange): Boolean = try {
    val config = store.load()
    val torrent = config.torrent
    store.save(
      config.copy(torrent = torrent.withEngineSettings(change.applyTo(torrent.engineSettings)))
    )
    log.i { "Saved the torrent settings a client changed" }
    true
  } catch (e: Exception) {
    log.w { "Could not save the torrent settings a client changed: ${e.describeCauses()}" }
    false
  }

  override fun close() {
    following?.cancel()
  }
}
