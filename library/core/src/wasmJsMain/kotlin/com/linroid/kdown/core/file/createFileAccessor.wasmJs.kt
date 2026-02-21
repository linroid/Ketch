package com.linroid.kdown.core.file

actual fun createFileAccessor(path: String): FileAccessor {
  return WasmFileAccessor()
}

private class WasmFileAccessor : FileAccessor {

  override suspend fun writeAt(offset: Long, data: ByteArray) {
    throw UnsupportedOperationException(
      "FileAccessor is not supported on Wasm/JS platform"
    )
  }

  override suspend fun flush() {
    throw UnsupportedOperationException(
      "FileAccessor is not supported on Wasm/JS platform"
    )
  }

  override fun close() {
    // No-op for Wasm/JS
  }

  override suspend fun delete() {
    throw UnsupportedOperationException(
      "FileAccessor is not supported on Wasm/JS platform"
    )
  }

  override suspend fun size(): Long {
    throw UnsupportedOperationException(
      "FileAccessor is not supported on Wasm/JS platform"
    )
  }

  override suspend fun preallocate(size: Long) {
    throw UnsupportedOperationException(
      "FileAccessor is not supported on Wasm/JS platform"
    )
  }
}
