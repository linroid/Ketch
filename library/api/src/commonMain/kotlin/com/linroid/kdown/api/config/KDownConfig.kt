package com.linroid.kdown.api.config

import kotlinx.serialization.Serializable

/**
 * Shared configuration for KDown apps and CLI.
 *
 * @property name user-visible name for this instance.
 *   When `null`, the app falls back to the platform default
 *   (e.g. device model on Android, hostname on desktop).
 * @property server server-mode settings (host, port, auth).
 * @property download download engine settings.
 * @property remote pre-configured remote server connections.
 */
@Serializable
data class KDownConfig(
  val name: String? = null,
  val server: ServerConfig = ServerConfig(),
  val download: DownloadConfig = DownloadConfig(),
  val remote: List<RemoteConfig> = emptyList(),
)
