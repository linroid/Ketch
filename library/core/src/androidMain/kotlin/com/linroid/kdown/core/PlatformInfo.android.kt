package com.linroid.kdown.core

import com.linroid.kdown.api.StorageInfo
import com.linroid.kdown.api.SystemInfo
import java.io.File

internal actual fun currentSystemInfo(): SystemInfo {
  val runtime = Runtime.getRuntime()
  return SystemInfo(
    os = "Android ${android.os.Build.VERSION.RELEASE}",
    arch = System.getProperty("os.arch", "unknown"),
    javaVersion = System.getProperty("java.version", "unknown"),
    availableProcessors = runtime.availableProcessors(),
    maxMemory = runtime.maxMemory(),
    totalMemory = runtime.totalMemory(),
    freeMemory = runtime.freeMemory(),
  )
}

internal actual fun currentStorageInfo(directory: String): StorageInfo {
  val dir = File(directory)
  return StorageInfo(
    downloadDirectory = dir.absolutePath,
    totalSpace = dir.totalSpace,
    freeSpace = dir.freeSpace,
    usableSpace = dir.usableSpace,
  )
}
