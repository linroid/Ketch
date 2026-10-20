package com.linroid.ketch.config

import com.akuleshov7.ktoml.Toml
import com.akuleshov7.ktoml.TomlIndentation
import com.akuleshov7.ktoml.TomlInputConfig
import com.akuleshov7.ktoml.TomlOutputConfig
import com.akuleshov7.ktoml.encoders.TomlMainEncoder

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
    private val outputConfig = TomlOutputConfig(
      indentation = TomlIndentation.NONE,
    )

    /** Shared [Toml] instance with no indentation. */
    internal val toml = Toml(
      inputConfig = TomlInputConfig(
        ignoreUnknownNames = true,
      ),
      outputConfig = outputConfig,
    )

    /**
     * Writes [config] as `config.toml`. Use this rather than [toml]'s `encodeToString`, whose
     * strings lose backslashes (see [escapeBackslashes]).
     */
    internal fun encode(config: KetchConfig): String {
      val tree = TomlMainEncoder.encode(
        KetchConfig.serializer(), config, outputConfig, toml.serializersModule
      )
      tree.escapeBackslashes()
      return toml.tomlWriter.writeToString(tree)
    }

    /**
     * Reads [text] as `config.toml`, with settings written by older versions moved to where this
     * one keeps them, such as the one provider of `[ai.llm]`, and folder paths they wrote
     * without some of their backslashes repaired.
     */
    internal fun decode(text: String): KetchConfig {
      val config = toml.decodeFromString(KetchConfig.serializer(), text)
      return config.copy(ai = config.ai.migrated()).repairLostBackslashes()
    }
  }
}
