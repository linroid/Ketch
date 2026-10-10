package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.app.i18n.AppLanguages
import com.linroid.ketch.app.i18n.PlatformLanguage
import com.linroid.ketch.app.i18n.appLanguageOf
import com.linroid.ketch.config.AccentColor
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.DesktopSettings
import com.linroid.ketch.config.IntegrationSettings
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.NotificationSettings
import com.linroid.ketch.config.PowerSettings
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

  /**
   * The language the UI shows: the tag of one of [AppLanguages], or `null` while it follows the
   * system. Android 13 and later and iOS keep it in their per-app language setting, the other
   * platforms in config.toml.
   */
  var language: String? by mutableStateOf(chosenLanguage())
    private set

  /** Whether Settings lists the languages to pick from; on iOS it opens the system's Settings. */
  val picksLanguageInApp: Boolean get() = PlatformLanguage.pickInApp

  init {
    if (!PlatformLanguage.systemKeepsChoice) {
      // A language kept in config.toml is the app's to apply; the system applies its own.
      language?.let(PlatformLanguage::apply)
    } else if (config.appearance.language != null) {
      // Saved before the system kept the choice (an Android update): it moved into the
      // system's per-app setting as the app started, so the config no longer holds it.
      update { it.copy(appearance = it.appearance.copy(language = null)) }
    }
  }

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
   * Shows the UI in [tag], the tag of one of [AppLanguages], or in the system's language for
   * `null`, from now on.
   */
  fun saveLanguage(tag: String?) {
    if (!PlatformLanguage.systemKeepsChoice) {
      update { it.copy(appearance = it.appearance.copy(language = tag)) }
    }
    PlatformLanguage.apply(tag)
    language = tag
  }

  /** Reads the language again, which the system's per-app setting may have changed. */
  fun refreshLanguage() {
    language = chosenLanguage()
  }

  /** Opens the system's settings for the app, where iOS chooses the app's language. */
  fun openLanguageSettings() = PlatformLanguage.openSystemSettings()

  private fun chosenLanguage(): String? {
    val tag = if (PlatformLanguage.systemKeepsChoice) {
      PlatformLanguage.systemChoice()
    } else {
      config.appearance.language
    }
    return appLanguageOf(tag)?.tag
  }

  /**
   * Persists a change to the remembered UI state. [transform] receives the stored preferences,
   * so concurrent changes to other fields are kept.
   */
  fun saveUi(transform: (UiPreferences) -> UiPreferences) {
    update { it.copy(ui = transform(it.ui)) }
  }

  /** Persists a change to the desktop app's window and startup behavior. */
  fun saveDesktop(transform: (DesktopSettings) -> DesktopSettings) {
    update { it.copy(desktop = transform(it.desktop)) }
  }

  /** Persists a change to which activity the apps report, and how. */
  fun saveNotifications(transform: (NotificationSettings) -> NotificationSettings) {
    update { it.copy(notifications = transform(it.notifications)) }
  }

  /** Persists a change to how the apps treat the power of the device they run on. */
  fun savePower(transform: (PowerSettings) -> PowerSettings) {
    update { it.copy(power = transform(it.power)) }
  }

  /** Persists a change to how the desktop app hooks into the operating system. */
  fun saveIntegration(transform: (IntegrationSettings) -> IntegrationSettings) {
    update { it.copy(integration = transform(it.integration)) }
  }

  /**
   * Reads the store again, for sections the host saves on its own, such as the desktop app's
   * close action after "Don't ask again".
   */
  fun reload() {
    configStore?.load()?.let { config = it }
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
  AccentColor.Indigo -> KetchAccent.Indigo
  AccentColor.Teal -> KetchAccent.Teal
  AccentColor.Green -> KetchAccent.Green
  AccentColor.Orange -> KetchAccent.Orange
}

/** Maps a theme palette back onto its persisted form. */
fun KetchAccent.toAccentColor(): AccentColor = when (this) {
  KetchAccent.Indigo -> AccentColor.Indigo
  KetchAccent.Teal -> AccentColor.Teal
  KetchAccent.Green -> AccentColor.Green
  KetchAccent.Orange -> AccentColor.Orange
}
