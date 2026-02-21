package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadPriority

fun extractFilename(url: String): String {
  val path = url.trim()
    .substringBefore("?")
    .substringBefore("#")
    .trimEnd('/')
    .substringAfterLast("/")
  return path.ifBlank { "" }
}

fun priorityLabel(priority: DownloadPriority): String {
  return when (priority) {
    DownloadPriority.LOW -> "Low"
    DownloadPriority.NORMAL -> "Normal"
    DownloadPriority.HIGH -> "High"
    DownloadPriority.URGENT -> "Urgent"
  }
}

fun formatBytes(bytes: Long): String {
  if (bytes < 0) return "--"
  val kb = 1024L
  val mb = kb * 1024
  val gb = mb * 1024
  return when {
    bytes < kb -> "$bytes B"
    bytes < mb -> {
      val tenths = (bytes * 10 + kb / 2) / kb
      "${tenths / 10}.${tenths % 10} KB"
    }
    bytes < gb -> {
      val tenths = (bytes * 10 + mb / 2) / mb
      "${tenths / 10}.${tenths % 10} MB"
    }
    else -> {
      val hundredths = (bytes * 100 + gb / 2) / gb
      "${hundredths / 100}.${
        (hundredths % 100)
          .toString().padStart(2, '0')
      } GB"
    }
  }
}

fun formatEta(seconds: Long): String {
  if (seconds <= 0) return ""
  val h = seconds / 3600
  val m = (seconds % 3600) / 60
  val s = seconds % 60
  return when {
    h > 0 -> "${h}h ${m}m"
    m > 0 -> "${m}m ${s}s"
    else -> "${s}s"
  }
}
