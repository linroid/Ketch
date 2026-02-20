package com.linroid.kdown.core

import com.linroid.kdown.api.StorageInfo
import com.linroid.kdown.api.SystemInfo

internal actual fun currentSystemInfo(): SystemInfo {
  return SystemInfo(
    os = "Browser",
    arch = "wasm",
    javaVersion = "N/A",
    availableProcessors = 1,
    maxMemory = 0L,
    totalMemory = 0L,
    freeMemory = 0L,
  )
}

internal actual fun currentStorageInfo(directory: String): StorageInfo {
  return StorageInfo(
    downloadDirectory = directory,
    totalSpace = 0L,
    freeSpace = 0L,
    usableSpace = 0L,
  )
}
