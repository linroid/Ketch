package com.linroid.kdown.app.config

import com.linroid.kdown.api.config.KDownConfig

/**
 * Reads and writes [KDownConfig] for the app.
 *
 * Platform implementations persist the config as TOML
 * (file-based on Android/JVM/iOS, localStorage on web).
 */
interface ConfigStore {
  /** Load saved config, or return defaults if none exists. */
  fun load(): KDownConfig

  /** Persist [config] for next launch. */
  fun save(config: KDownConfig)
}
