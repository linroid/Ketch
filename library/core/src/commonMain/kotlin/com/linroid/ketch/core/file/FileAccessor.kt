package com.linroid.ketch.core.file

/**
 * Platform-specific random-access file writer.
 *
 * Each platform provides an implementation via [createFileAccessor]:
 * - **Android/JVM/iOS/JS/WasmWasi**: okio `FileHandle` via [PathFileAccessor]
 * - **Android content URIs**: `ContentUriFileAccessor` for SAF-backed storage
 *
 * Blocking I/O runs on the dispatcher given to [createFileAccessor], which Ketch takes from
 * [KetchDispatchers.io][com.linroid.ketch.core.KetchDispatchers.io]. Implementations are
 * thread-safe: each serializes its operations on that dispatcher.
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

  /**
   * Pre-allocates [size] bytes on disk to avoid fragmentation.
   * A zero size creates an empty destination or truncates an existing destination to zero bytes.
   */
  suspend fun preallocate(size: Long)
}
