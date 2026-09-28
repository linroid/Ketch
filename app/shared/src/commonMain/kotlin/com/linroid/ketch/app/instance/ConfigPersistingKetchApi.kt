package com.linroid.ketch.app.instance

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.config.ConfigStore

/**
 * Saves every accepted [updateConfig] to [store], so download settings
 * changed at runtime, in the app or by a remote client through the local
 * server, are used again on the next launch.
 */
internal class ConfigPersistingKetchApi(
  private val delegate: KetchApi,
  private val store: ConfigStore,
) : KetchApi by delegate {
  override suspend fun updateConfig(config: DownloadConfig) {
    delegate.updateConfig(config)
    store.save(store.load().copy(download = config))
  }
}
