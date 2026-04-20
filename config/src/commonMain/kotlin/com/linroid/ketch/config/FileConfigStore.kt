package com.linroid.ketch.config

import okio.Path.Companion.toPath
import okio.buffer

/**
 * File-based [ConfigStore] that persists config as TOML.
 *
 * Uses a write-to-tmp-then-rename strategy for crash safety:
 * new content is written to `<path>.tmp`, then atomically
 * moved to [path]. On load, a leftover `.tmp` file from an
 * interrupted save is recovered or cleaned up.
 */
class FileConfigStore(private val path: String) : ConfigStore {
  private val tmpPath = "$path.tmp"

  override fun load(): KetchConfig {
    val file = path.toPath()
    val tmp = tmpPath.toPath()
    if (!platformFileSystem.exists(file) &&
      platformFileSystem.exists(tmp)
    ) {
      // Previous save wrote .tmp but didn't rename — recover
      platformFileSystem.atomicMove(tmp, file)
    } else if (platformFileSystem.exists(tmp)) {
      // Main file exists, leftover .tmp is stale — remove
      platformFileSystem.delete(tmp)
    }
    if (!platformFileSystem.exists(file)) return KetchConfig()
    val source = platformFileSystem.source(file).buffer()
    val content = try {
      source.readUtf8()
    } finally {
      source.close()
    }
    return ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(), content,
    )
  }

  override fun save(config: KetchConfig) {
    val file = path.toPath()
    val tmp = tmpPath.toPath()
    file.parent?.let { platformFileSystem.createDirectories(it) }
    val sink = platformFileSystem.sink(tmp).buffer()
    try {
      val encoded = ConfigStore.toml
        .encodeToString(KetchConfig.serializer(), config)
      sink.writeUtf8(encoded)
    } finally {
      sink.close()
    }
    platformFileSystem.atomicMove(tmp, file)
  }
}
