package com.linroid.kdown.core.file

import kotlinx.io.files.Path

expect class FileAccessor(path: Path) {
  suspend fun writeAt(offset: Long, data: ByteArray)
  suspend fun flush()
  fun close()
  suspend fun delete()
  suspend fun size(): Long
  suspend fun preallocate(size: Long)
}
