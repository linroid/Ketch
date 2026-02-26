package com.linroid.ketch.core.file

import okio.FileSystem

internal actual val platformFileSystem: FileSystem
  get() = throw UnsupportedOperationException(
    "FileSystem is not supported on Wasm/JS platform"
  )
