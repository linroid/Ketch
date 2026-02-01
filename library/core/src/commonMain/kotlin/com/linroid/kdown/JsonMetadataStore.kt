package com.linroid.kdown

import com.linroid.kdown.model.DownloadMetadata
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.io.buffered
import kotlinx.io.files.FileSystem
import kotlinx.io.files.Path
import kotlinx.io.files.SystemFileSystem
import kotlinx.io.readString
import kotlinx.io.writeString
import kotlinx.serialization.json.Json

class JsonMetadataStore(
  private val directory: Path,
  private val fileSystem: FileSystem = SystemFileSystem
) : MetadataStore {
  private val mutex = Mutex()
  private val json = Json {
    prettyPrint = true
    ignoreUnknownKeys = true
  }

  init {
    if (!fileSystem.exists(directory)) {
      fileSystem.createDirectories(directory)
    }
  }

  private fun pathFor(taskId: String): Path {
    val safeTaskId = taskId.replace(Regex("[^a-zA-Z0-9_-]"), "_")
    return Path(directory, "$safeTaskId.json")
  }

  override suspend fun load(taskId: String): DownloadMetadata? = mutex.withLock {
    val path = pathFor(taskId)
    if (fileSystem.exists(path)) {
      try {
        val content = fileSystem.source(path).buffered().readString()
        json.decodeFromString<DownloadMetadata>(content)
      } catch (e: Exception) {
        null
      }
    } else {
      null
    }
  }

  override suspend fun save(taskId: String, metadata: DownloadMetadata) = mutex.withLock {
    val path = pathFor(taskId)
    val content = json.encodeToString(DownloadMetadata.serializer(), metadata)
    fileSystem.sink(path).buffered().use { sink ->
      sink.writeString(content)
    }
  }

  override suspend fun clear(taskId: String) = mutex.withLock {
    val path = pathFor(taskId)
    if (fileSystem.exists(path)) {
      fileSystem.delete(path)
    }
  }

  suspend fun clearAll() = mutex.withLock {
    fileSystem.list(directory).forEach { path ->
      if (path.name.endsWith(".json")) {
        fileSystem.delete(path)
      }
    }
  }
}
