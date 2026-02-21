package com.linroid.kdown.core

import com.linroid.kdown.api.SystemInfo
import java.io.File

internal actual fun currentSystemInfo(directory: String): SystemInfo {
  val runtime = Runtime.getRuntime()
  val dir = File(directory)
  return SystemInfo(
    os = System.getProperty("os.name", "unknown"),
    arch = System.getProperty("os.arch", "unknown"),
    separator = File.separator,
    javaVersion = System.getProperty("java.version", "unknown"),
    availableProcessors = runtime.availableProcessors(),
    maxMemory = runtime.maxMemory(),
    totalMemory = runtime.totalMemory(),
    freeMemory = runtime.freeMemory(),
    downloadDirectory = dir.absolutePath,
    totalSpace = dir.totalSpace,
    freeSpace = dir.freeSpace,
    usableSpace = dir.usableSpace,
  )
}
