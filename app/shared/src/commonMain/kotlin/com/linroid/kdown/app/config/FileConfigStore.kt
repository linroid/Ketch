package com.linroid.kdown.app.config

import com.linroid.kdown.api.config.KDownConfig
import kotlinx.io.buffered
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString

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

  override fun load(): KDownConfig {
    val file = Path(path)
    val tmp = Path(tmpPath)
    if (!SystemFileSystem.exists(file) &&
      SystemFileSystem.exists(tmp)
    ) {
      // Previous save wrote .tmp but didn't rename — recover
      SystemFileSystem.atomicMove(tmp, file)
    } else if (SystemFileSystem.exists(tmp)) {
      // Main file exists, leftover .tmp is stale — remove
      SystemFileSystem.delete(tmp)
    }
    if (!SystemFileSystem.exists(file)) return KDownConfig()
    val content = SystemFileSystem.source(file).buffered()
      .use { it.readString() }
    return ConfigStore.toml.decodeFromString(
      KDownConfig.serializer(), content,
    )
  }

  override fun save(config: KDownConfig) {
    val file = Path(path)
    val tmp = Path(tmpPath)
    file.parent?.let { SystemFileSystem.createDirectories(it) }
    SystemFileSystem.sink(tmp).buffered().use { sink ->
      val encoded = ConfigStore.toml
        .encodeToString(KDownConfig.serializer(), config)
      sink.writeString(encoded)
    }
    SystemFileSystem.atomicMove(tmp, file)
  }
}
