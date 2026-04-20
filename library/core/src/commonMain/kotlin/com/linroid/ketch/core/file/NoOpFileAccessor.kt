package com.linroid.ketch.core.file

/**
 * Stub [FileAccessor] that does nothing.
 *
 * Used when a [DownloadSource][com.linroid.ketch.core.engine.DownloadSource]
 * manages its own file I/O (e.g., libtorrent). All operations are
 * no-ops to satisfy the type system without touching the file system.
 */
internal object NoOpFileAccessor : FileAccessor {
  override suspend fun writeAt(offset: Long, data: ByteArray) = Unit
  override suspend fun flush() = Unit
  override fun close() = Unit
  override suspend fun delete() = Unit
  override suspend fun size(): Long = 0L
  override suspend fun preallocate(size: Long) = Unit
}
