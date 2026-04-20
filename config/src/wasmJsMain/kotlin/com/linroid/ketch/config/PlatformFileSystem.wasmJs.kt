package com.linroid.ketch.config

import okio.FileSystem

internal actual val platformFileSystem: FileSystem
  get() = throw UnsupportedOperationException(
    "FileSystem is not supported on Wasm/JS platform"
  )
