package com.linroid.kdown.app.config

import com.linroid.kdown.api.config.KDownConfig
import java.io.File

/**
 * File-based [ConfigStore] that persists config as TOML.
 *
 * Used on JVM/Desktop and Android.
 */
class FileConfigStore(private val path: String) : ConfigStore {
  override fun load(): KDownConfig {
    val file = File(path)
    if (!file.exists()) return KDownConfig()
    return KDownConfig.fromToml(file.readText())
  }

  override fun save(config: KDownConfig) {
    val file = File(path)
    file.parentFile?.mkdirs()
    file.writeText(config.toToml())
  }
}
