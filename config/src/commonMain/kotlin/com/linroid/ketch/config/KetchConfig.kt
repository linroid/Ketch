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
 */
@Serializable
data class KetchConfig(
  val name: String? = null,
  val server: ServerConfig = ServerConfig(),
  val download: DownloadConfig = DownloadConfig(),
  val remotes: List<RemoteConfig> = emptyList(),
)
