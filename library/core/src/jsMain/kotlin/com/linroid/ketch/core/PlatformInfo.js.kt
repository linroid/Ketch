package com.linroid.ketch.core

import com.linroid.ketch.api.SystemInfo

internal actual fun currentSystemInfo(directory: String?): SystemInfo {
  val default = defaultDownloadDirectory()
  return SystemInfo(
    os = "Node.js",
    arch = "js",
    separator = "/",
    javaVersion = "N/A",
    availableProcessors = 1,
    maxMemory = 0L,
    totalMemory = 0L,
    freeMemory = 0L,
    downloadDirectory = directory ?: default,
    defaultDownloadDirectory = default,
    totalSpace = 0L,
    freeSpace = 0L,
    usableSpace = 0L,
  )
}

internal actual fun defaultDownloadDirectory(): String = "downloads"
