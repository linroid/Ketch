package com.linroid.kdown.core

import com.linroid.kdown.api.SystemInfo

internal actual fun currentSystemInfo(directory: String): SystemInfo {
  return SystemInfo(
    os = "Browser",
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
