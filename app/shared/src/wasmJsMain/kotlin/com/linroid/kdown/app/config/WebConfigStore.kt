package com.linroid.kdown.app.config

import com.linroid.kdown.api.config.KDownConfig
import kotlinx.browser.window

/**
 * Web [ConfigStore] that persists config as TOML in localStorage.
 */
class WebConfigStore(
  private val key: String = "kdown-config",
) : ConfigStore {
  override fun load(): KDownConfig {
    val toml = window.localStorage.getItem(key) ?: return KDownConfig()
    return KDownConfig.fromToml(toml)
  }

  override fun save(config: KDownConfig) {
    window.localStorage.setItem(key, config.toToml())
  }
}
