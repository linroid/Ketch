package com.linroid.ketch.app.config

import com.linroid.ketch.api.config.KetchConfig
import kotlinx.browser.window

/**
 * Web [ConfigStore] that persists config as TOML in localStorage.
 */
class WebConfigStore(
  private val key: String = "ketch-config",
) : ConfigStore {
  override fun load(): KetchConfig {
    val content = window.localStorage.getItem(key)
      ?: return KetchConfig()
    return ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(), content,
    )
  }

  override fun save(config: KetchConfig) {
    window.localStorage.setItem(
      key,
      ConfigStore.toml.encodeToString(
        KetchConfig.serializer(), config,
      ),
    )
  }
}
