package com.linroid.kdown.app.config

import com.linroid.kdown.api.config.KDownConfig
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.stringByAppendingPathComponent
import platform.Foundation.writeToFile

/**
 * File-based [ConfigStore] for iOS using Foundation APIs.
 *
 * Stores config as `config.toml` in the app's Documents directory.
 */
class FileConfigStore(private val path: String) : ConfigStore {

  constructor() : this(defaultConfigPath())

  override fun load(): KDownConfig {
    val content = NSString.stringWithContentsOfFile(
      path,
      encoding = NSUTF8StringEncoding,
      error = null,
    ) ?: return KDownConfig()
    return KDownConfig.fromToml(content)
  }

  override fun save(config: KDownConfig) {
    val toml = config.toToml()
    val nsString = NSString.create(string = toml)
    nsString.writeToFile(
      path,
      atomically = true,
      encoding = NSUTF8StringEncoding,
      error = null,
    )
  }
}

private fun defaultConfigPath(): String {
  @Suppress("UNCHECKED_CAST")
  val docsDir = (NSSearchPathForDirectoriesInDomains(
    NSDocumentDirectory, NSUserDomainMask, true,
  ) as List<String>).first()
  return (docsDir as NSString)
    .stringByAppendingPathComponent("config.toml")
}
