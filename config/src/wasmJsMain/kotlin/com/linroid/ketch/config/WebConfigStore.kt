package com.linroid.ketch.config

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
    return ConfigStore.decode(content)
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
