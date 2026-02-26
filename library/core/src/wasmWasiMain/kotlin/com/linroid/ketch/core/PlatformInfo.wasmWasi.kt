package com.linroid.ketch.core

import com.linroid.ketch.api.SystemInfo

internal actual fun currentSystemInfo(directory: String): SystemInfo {
  return SystemInfo(
    os = "WASI",
    arch = "wasm",
    separator = "/",
    javaVersion = "N/A",
    availableProcessors = 1,
    maxMemory = 0L,
    totalMemory = 0L,
    freeMemory = 0L,
    downloadDirectory = directory,
    totalSpace = 0L,
    freeSpace = 0L,
    usableSpace = 0L,
  )
}
