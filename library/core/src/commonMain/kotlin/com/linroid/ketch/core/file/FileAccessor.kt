package com.linroid.ketch.core.file

/**
 * Platform-specific random-access file writer.
 *
 * Each platform provides an implementation via [createFileAccessor]:
 * - **Android/JVM/iOS**: okio `FileHandle` via [PathFileAccessor] with `Dispatchers.IO`
 * - **Android content URIs**: `ContentUriFileAccessor` for SAF-backed storage
 * - **WasmJs**: Stub that throws `UnsupportedOperationException` (no file I/O)
 *
 * Android, JVM, and iOS implementations are thread-safe (serialized dispatcher).
 */
interface FileAccessor {
  /** Writes [data] starting at the given byte [offset]. */
  suspend fun writeAt(offset: Long, data: ByteArray)

  /** Flushes buffered writes to disk. */
  suspend fun flush()

  /** Closes the underlying file handle. */
  fun close()

  /** Deletes the file from disk. */
  suspend fun delete()

  /** Returns the current file size in bytes. */
  suspend fun size(): Long

  /** Pre-allocates [size] bytes on disk to avoid fragmentation. */
  suspend fun preallocate(size: Long)
}
