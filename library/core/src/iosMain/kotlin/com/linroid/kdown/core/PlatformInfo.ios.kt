package com.linroid.kdown.core

import com.linroid.kdown.api.SystemInfo
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileSystemSize
import platform.Foundation.NSProcessInfo

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentSystemInfo(directory: String): SystemInfo {
  val info = NSProcessInfo.processInfo
  val fm = NSFileManager.defaultManager
  val attrs = fm.attributesOfFileSystemForPath(directory, null)
  val totalSpace = (attrs?.get(NSFileSystemSize) as? Number)
    ?.toLong() ?: 0L
  val freeSpace = (attrs?.get(NSFileSystemFreeSize) as? Number)
    ?.toLong() ?: 0L
  return SystemInfo(
    os = "iOS ${info.operatingSystemVersionString}",
    arch = "arm64",
    separator = "/",
    javaVersion = "N/A",
    availableProcessors = info.activeProcessorCount.toInt(),
    maxMemory = info.physicalMemory.toLong(),
    totalMemory = info.physicalMemory.toLong(),
    freeMemory = 0L,
    downloadDirectory = directory,
    totalSpace = totalSpace,
    freeSpace = freeSpace,
    usableSpace = freeSpace,
  )
}
