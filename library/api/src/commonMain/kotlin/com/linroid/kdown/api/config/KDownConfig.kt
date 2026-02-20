package com.linroid.kdown.api.config

import kotlinx.serialization.Serializable

/**
 * Shared configuration for KDown apps and CLI.
 *
 * @property server server-mode settings (host, port, auth).
 * @property download download engine settings.
 * @property remote pre-configured remote server connections.
 */
@Serializable
data class KDownConfig(
  val server: ServerConfig = ServerConfig(),
  val download: DownloadConfig = DownloadConfig(),
  val remote: List<RemoteConfig> = emptyList(),
)
