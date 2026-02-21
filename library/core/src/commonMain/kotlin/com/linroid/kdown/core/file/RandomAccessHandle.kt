package com.linroid.kdown.core.file

/**
 * Low-level random-access write handle.
 *
 * Implementations are called within `Dispatchers.IO` + Mutex by
 * [PathFileAccessor], so they need not be thread-safe themselves.
 */
internal interface RandomAccessHandle {
  /** Writes [data] starting at the given byte [offset]. */
  fun writeAt(offset: Long, data: ByteArray)

  /** Flushes buffered writes to disk. */
  fun flush()

  /** Closes the underlying handle. */
  fun close()

  /** Pre-allocates [size] bytes on disk to avoid fragmentation. */
  fun preallocate(size: Long)
}
