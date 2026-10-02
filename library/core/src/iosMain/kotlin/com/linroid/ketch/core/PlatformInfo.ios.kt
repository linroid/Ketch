package com.linroid.ketch.core

import com.linroid.ketch.api.SystemInfo
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileSystemFreeSize
import platform.Foundation.NSFileSystemSize
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSUserDomainMask

@OptIn(ExperimentalForeignApi::class)
internal actual fun currentSystemInfo(directory: String?): SystemInfo {
  val info = NSProcessInfo.processInfo
  val default = defaultDownloadDirectory()
  val folder = directory ?: default
  val fm = NSFileManager.defaultManager
  // The default folder is created with the first download; until then, ask its parent.
  val attrs = fm.attributesOfFileSystemForPath(folder, null)
    ?: fm.attributesOfFileSystemForPath(folder.substringBeforeLast('/'), null)
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
    downloadDirectory = folder,
    defaultDownloadDirectory = default,
    totalSpace = totalSpace,
    freeSpace = freeSpace,
    usableSpace = freeSpace,
  )
}

/**
 * `Downloads` in the app's Documents folder, which the Files app shows as On My iPhone › <app> ›
 * Downloads. It is created with the first download saved there.
 */
@Suppress("UNCHECKED_CAST")
internal actual fun defaultDownloadDirectory(): String =
  (NSSearchPathForDirectoriesInDomains(
    NSDocumentDirectory, NSUserDomainMask, true,
  ) as List<String>).first() + "/Downloads"
