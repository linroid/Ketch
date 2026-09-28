package com.linroid.ketch.core.file

import okio.IOException
import okio.Path.Companion.toPath

/**
 * Checks that [directory] is an existing folder, so a bad download folder
 * is rejected when it is configured instead of failing every download
 * later. Android `content://` documents are not file system paths and are
 * accepted as they are; any other URL is rejected.
 *
 * @throws IllegalArgumentException if [directory] is blank, a URL this
 *   platform cannot write to, missing, or not a folder
 */
internal fun requireDownloadDirectory(directory: String) {
  require(directory.isNotBlank()) { "Download folder must not be blank" }
  if (isContentUri(directory)) return
  require("://" !in directory) { "Download folder must be a folder path, not a URL: $directory" }
  val metadata = try {
    platformFileSystem.metadataOrNull(directory.toPath())
  } catch (e: IOException) {
    throw IllegalArgumentException("Cannot read download folder $directory: ${e.message}", e)
  }
  require(metadata != null) { "Download folder does not exist: $directory" }
  require(metadata.isDirectory) { "Download folder is not a folder: $directory" }
}
