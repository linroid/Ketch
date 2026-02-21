package com.linroid.kdown.app.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlIndentation
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.TomlOutputConfig
import com.linroid.kdown.api.config.KDownConfig

/**
 * Reads and writes [KDownConfig] for the app.
 *
 * Implementations persist the config as TOML (file-based on
 * Android/JVM/iOS, localStorage on web).
 */
interface ConfigStore {
  /** Load saved config, or return defaults if none exists. */
  fun load(): KDownConfig

  /** Persist [config] for next launch. */
  fun save(config: KDownConfig)

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
