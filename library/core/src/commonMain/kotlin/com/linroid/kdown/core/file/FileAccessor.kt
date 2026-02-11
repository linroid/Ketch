package com.linroid.kdown.core.file

import kotlinx.io.files.Path

/**
 * Platform-specific random-access file writer.
 *
 * Each platform provides an actual implementation:
 * - **Android/JVM**: `RandomAccessFile` with `Dispatchers.IO`
 * - **iOS**: Foundation `NSFileHandle` / `NSFileManager` with `Dispatchers.IO`
 * - **WasmJs**: Stub that throws `UnsupportedOperationException` (no file I/O)
 *
 * Android, JVM, and iOS implementations are thread-safe (protected by a `Mutex`).
 *
 * @param path the file system path to write to
 */
expect class FileAccessor(path: Path) {
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
