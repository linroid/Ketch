package com.linroid.kdown.api.config

import com.akuleshov7.ktoml.Toml
import kotlinx.serialization.Serializable

/**
 * Shared configuration for KDown apps and CLI.
 *
 * Serialized as TOML via [fromToml] / [toToml].
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
) {
  /** Serialize this config to a TOML string. */
  fun toToml(): String =
    Toml.encodeToString(serializer(), this)

  companion object {
    /** Parse a [KDownConfig] from a TOML string. */
    fun fromToml(content: String): KDownConfig =
      Toml.decodeFromString(serializer(), content)

    /** Returns a config with all defaults. */
    fun default(): KDownConfig = KDownConfig()
  }
}
