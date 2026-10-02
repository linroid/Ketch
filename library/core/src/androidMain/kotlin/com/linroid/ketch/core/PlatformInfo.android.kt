@file:Suppress("UseKtx")

package com.linroid.ketch.core

import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import com.linroid.ketch.api.SystemInfo
import com.linroid.ketch.core.file.isContentUri
import java.io.File

internal actual fun currentSystemInfo(directory: String): SystemInfo {
  val runtime = Runtime.getRuntime()
  // A folder picked through the system is a content:// tree, which File would turn into a
  // relative path; its space is that of the storage volume it lies on.
  val tree = isContentUri(directory)
  val dir = if (tree) treeVolume(directory) else File(directory)
  return SystemInfo(
    os = "Android ${android.os.Build.VERSION.RELEASE}",
    arch = System.getProperty("os.arch", "unknown"),
    separator = File.separator,
    javaVersion = System.getProperty("java.version", "unknown"),
    availableProcessors = runtime.availableProcessors(),
    maxMemory = runtime.maxMemory(),
    totalMemory = runtime.totalMemory(),
    freeMemory = runtime.freeMemory(),
    downloadDirectory = if (tree) directory else File(directory).absolutePath,
    totalSpace = dir?.totalSpace ?: 0,
    freeSpace = dir?.freeSpace ?: 0,
    usableSpace = dir?.usableSpace ?: 0,
  )
}

internal actual fun defaultDownloadDirectory(): String {
  val context = AndroidContext.get()
  val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS)
    ?: File(context.filesDir, "downloads")
  return dir.absolutePath
}

/**
 * This app's folder on the storage volume holding the folder [tree], such as
 * `…/tree/primary%3ADownload` on the shared storage; `null` when another provider owns [tree].
 */
private fun treeVolume(tree: String): File? {
  val uri = Uri.parse(tree)
  if (uri.authority != EXTERNAL_STORAGE_AUTHORITY || !DocumentsContract.isTreeUri(uri)) return null
  val volume = DocumentsContract.getTreeDocumentId(uri).substringBefore(':')
  // The first folder is on the primary volume, the others on removable ones by their id.
  val folders = AndroidContext.get().getExternalFilesDirs(null)
  return if (volume == PRIMARY_VOLUME) {
    folders.firstOrNull()
  } else {
    folders.filterNotNull().firstOrNull { it.path.startsWith("/storage/$volume/") }
  }
}

private const val EXTERNAL_STORAGE_AUTHORITY = "com.android.externalstorage.documents"
private const val PRIMARY_VOLUME = "primary"
