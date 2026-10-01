package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.config.AccentColor
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.ServerConfig
import com.linroid.ketch.config.ThemeMode
import com.linroid.ketch.config.TorrentSettings
import com.linroid.ketch.config.UiPreferences
import com.linroid.ketch.app.theme.KetchAccent

/**
 * Owns the non-AI parts of [KetchConfig] for the settings page.
 *
 * Every save is a load-modify-write against the store, so this and
 * [AiSettingsController] can persist their own sections without
 * overwriting each other's.
 *
 * @param configStore store backing `config.toml`; `null` keeps the
 *   settings in memory only.
 */
class AppSettingsController(
  private val configStore: ConfigStore? = null,
) {
  /** Config as last loaded or saved. */
  var config by mutableStateOf(configStore?.load() ?: KetchConfig())
    private set

  /** Accent palette the UI should be themed with. */
  val accent: KetchAccent get() = config.appearance.accent.toKetchAccent()

  /** Whether the UI follows the system or forces light or dark. */
  val themeMode: ThemeMode get() = config.appearance.theme

  /** UI state remembered between launches. */
  val ui: UiPreferences get() = config.ui

  /** Persists the instance name; blank clears it back to the default. */
  fun saveName(name: String) {
    update { it.copy(name = name.trim().ifBlank { null }) }
  }

  /** Persists download engine settings. */
  fun saveDownload(download: DownloadConfig) {
    update { it.copy(download = download) }
  }

  /** Persists the embedded instance's BitTorrent settings. */
  fun saveTorrent(torrent: TorrentSettings) {
    update { it.copy(torrent = torrent) }
  }

  /** Persists daemon server settings. */
  fun saveServer(server: ServerConfig) {
    update { it.copy(server = server) }
  }

  /** Persists the accent palette. */
  fun saveAccent(accent: KetchAccent) {
    update {
      it.copy(appearance = it.appearance.copy(accent = accent.toAccentColor()))
    }
  }

  /** Persists the light/dark mode. */
  fun saveThemeMode(mode: ThemeMode) {
    update { it.copy(appearance = it.appearance.copy(theme = mode)) }
  }

  /**
   * Persists a change to the remembered UI state. [transform] receives the stored preferences,
   * so concurrent changes to other fields are kept.
   */
  fun saveUi(transform: (UiPreferences) -> UiPreferences) {
    update { it.copy(ui = transform(it.ui)) }
  }

  private fun update(block: (KetchConfig) -> KetchConfig) {
    val store = configStore
    val current = store?.load() ?: config
    val updated = block(current)
    store?.save(updated)
    config = updated
  }
}

/** Maps the persisted accent onto the theme's palette. */
fun AccentColor.toKetchAccent(): KetchAccent = when (this) {
  AccentColor.Signal -> KetchAccent.Signal
  AccentColor.Harbor -> KetchAccent.Harbor
  AccentColor.Fathom -> KetchAccent.Fathom
  AccentColor.Beacon -> KetchAccent.Beacon
}

/** Maps a theme palette back onto its persisted form. */
fun KetchAccent.toAccentColor(): AccentColor = when (this) {
  KetchAccent.Signal -> AccentColor.Signal
  KetchAccent.Harbor -> AccentColor.Harbor
  KetchAccent.Fathom -> AccentColor.Fathom
  KetchAccent.Beacon -> AccentColor.Beacon
}
