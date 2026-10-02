package com.linroid.ketch.core

import android.os.Environment
import com.linroid.ketch.api.SystemInfo
import java.io.File

internal actual fun currentSystemInfo(directory: String?): SystemInfo {
  val runtime = Runtime.getRuntime()
  val default = File(defaultDownloadDirectory())
  val dir = directory?.let(::File) ?: default
  return SystemInfo(
    os = "Android ${android.os.Build.VERSION.RELEASE}",
    arch = System.getProperty("os.arch", "unknown"),
    separator = File.separator,
    javaVersion = System.getProperty("java.version", "unknown"),
    availableProcessors = runtime.availableProcessors(),
    maxMemory = runtime.maxMemory(),
    totalMemory = runtime.totalMemory(),
    freeMemory = runtime.freeMemory(),
    downloadDirectory = dir.absolutePath,
    defaultDownloadDirectory = default.absolutePath,
    totalSpace = dir.totalSpace,
    freeSpace = dir.freeSpace,
    usableSpace = dir.usableSpace,
  )
}

internal actual fun defaultDownloadDirectory(): String {
  val context = AndroidContext.get()
  val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
    ?: File(context.filesDir, "downloads")
  return dir.absolutePath
}
