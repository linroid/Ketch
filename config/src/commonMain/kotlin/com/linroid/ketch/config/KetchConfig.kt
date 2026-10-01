package com.linroid.ketch.config

import com.linroid.ketch.api.DownloadConfig
import kotlinx.serialization.Serializable

/**
 * Shared configuration for Ketch apps and CLI.
 *
 * @property name user-visible name for this instance.
 *   When `null`, the app falls back to the platform default
 *   (e.g. device model on Android, hostname on desktop).
 * @property server server-mode settings (host, port, auth).
 * @property download download engine settings.
 * @property remotes pre-configured remote server connections.
 * @property ai AI resource discovery settings.
 * @property appearance app look and feel settings.
 * @property torrent BitTorrent engine settings.
 * @property speed speed modes and rules for the embedded device.
 * @property ui app UI state remembered between launches.
 * @property desktop desktop app window and startup behavior.
 * @property notifications which activity the apps report.
 * @property integration operating system integration of the desktop app.
 */
@Serializable
data class KetchConfig(
  val name: String? = null,
  val server: ServerConfig = ServerConfig(),
  val download: DownloadConfig = DownloadConfig(),
  val remotes: List<RemoteConfig> = emptyList(),
  val ai: AiSettings = AiSettings(),
  val appearance: AppearanceConfig = AppearanceConfig(),
  val torrent: TorrentSettings = TorrentSettings(),
  val speed: SpeedSettings = SpeedSettings(),
  val ui: UiPreferences = UiPreferences(),
  val desktop: DesktopSettings = DesktopSettings(),
  val notifications: NotificationSettings = NotificationSettings(),
  val integration: IntegrationSettings = IntegrationSettings(),
)
