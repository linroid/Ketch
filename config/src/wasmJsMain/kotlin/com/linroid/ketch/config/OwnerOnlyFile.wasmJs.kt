package com.linroid.ketch.config

internal actual fun writeOwnerOnly(path: String, content: String) {
  throw UnsupportedOperationException("Files are not supported on Wasm/JS platform")
}
