package com.linroid.ketch.app.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlIndentation
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.TomlOutputConfig
import com.linroid.ketch.api.config.KetchConfig

/**
 * Reads and writes [KetchConfig] for the app.
 *
 * Implementations persist the config as TOML (file-based on
 * Android/JVM/iOS, localStorage on web).
 */
interface ConfigStore {
  /** Load saved config, or return defaults if none exists. */
  fun load(): KetchConfig

  /** Persist [config] for next launch. */
  fun save(config: KetchConfig)

  companion object {
    /** Shared [Toml] instance with no indentation. */
    internal val toml = Toml(
      inputConfig = TomlInputConfig(
        ignoreUnknownNames = true,
      ),
      outputConfig = TomlOutputConfig(
        indentation = TomlIndentation.NONE,
      ),
    )
  }
}
