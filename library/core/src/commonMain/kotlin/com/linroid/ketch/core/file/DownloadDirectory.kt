package com.linroid.ketch.core.file

import okio.IOException
import okio.Path.Companion.toPath

/**
 * Checks that [directory] is an existing folder, so a bad download folder
 * is rejected when it is configured instead of failing every download
 * later. URIs such as Android `content://` trees are not file system
 * paths and are accepted as they are.
 *
 * @throws IllegalArgumentException if [directory] is blank, missing, or
 *   not a folder
 */
internal fun requireDownloadDirectory(directory: String) {
  require(directory.isNotBlank()) { "Download folder must not be blank" }
  if ("://" in directory) return
  val metadata = try {
    platformFileSystem.metadataOrNull(directory.toPath())
  } catch (e: IOException) {
    throw IllegalArgumentException("Cannot read download folder $directory: ${e.message}", e)
  }
  require(metadata != null) { "Download folder does not exist: $directory" }
  require(metadata.isDirectory) { "Download folder is not a folder: $directory" }
}
