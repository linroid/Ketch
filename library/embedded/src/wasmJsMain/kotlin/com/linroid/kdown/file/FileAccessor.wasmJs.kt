package com.linroid.kdown.file

import kotlinx.io.files.Path

actual class FileAccessor actual constructor(private val path: Path) {

  actual suspend fun writeAt(offset: Long, data: ByteArray) {
    throw UnsupportedOperationException("FileAccessor is not supported on Wasm/JS platform")
  }

  actual suspend fun flush() {
    throw UnsupportedOperationException("FileAccessor is not supported on Wasm/JS platform")
  }

  actual fun close() {
    // No-op for Wasm/JS
  }

  actual suspend fun delete() {
    throw UnsupportedOperationException("FileAccessor is not supported on Wasm/JS platform")
  }

  actual suspend fun size(): Long {
    throw UnsupportedOperationException("FileAccessor is not supported on Wasm/JS platform")
  }

  actual suspend fun preallocate(size: Long) {
    throw UnsupportedOperationException("FileAccessor is not supported on Wasm/JS platform")
  }
}
