package com.linroid.kdown.core

import com.linroid.kdown.api.StorageInfo
import com.linroid.kdown.api.SystemInfo
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileSystemSize
import platform.Foundation.NSProcessInfo

internal actual fun currentSystemInfo(): SystemInfo {
  val info = NSProcessInfo.processInfo
  return SystemInfo(
    os = "iOS ${info.operatingSystemVersionString}",
    arch = "arm64",
    javaVersion = "N/A",
    availableProcessors = info.activeProcessorCount.toInt(),
    maxMemory = info.physicalMemory.toLong(),
    totalMemory = info.physicalMemory.toLong(),
    freeMemory = 0L,
  )
}

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentStorageInfo(directory: String): StorageInfo {
  val fm = NSFileManager.defaultManager
  val attrs = fm.attributesOfFileSystemForPath(directory, null)
  val totalSpace = (attrs?.get(NSFileSystemSize) as? Number)
    ?.toLong() ?: 0L
  val freeSpace = (attrs?.get(NSFileSystemFreeSize) as? Number)
    ?.toLong() ?: 0L
  return StorageInfo(
    downloadDirectory = directory,
    totalSpace = totalSpace,
    freeSpace = freeSpace,
    usableSpace = freeSpace,
  )
}
