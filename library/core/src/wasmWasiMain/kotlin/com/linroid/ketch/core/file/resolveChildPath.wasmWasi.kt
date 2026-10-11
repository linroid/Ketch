package com.linroid.ketch.core.file

import okio.Path.Companion.toPath

internal actual fun resolveChildPath(
  directory: String,
  fileName: String,
): String = (directory.toPath() / fileName).toString()

internal actual fun resolveChildFolder(
  directory: String,
  folderName: String,
): String = (directory.toPath() / folderName).toString()

internal actual fun isContentUri(directory: String): Boolean = false

internal actual fun createPartialDocument(
  directory: String,
  fileName: String,
): CreatedDocument? = null

internal actual fun finishPartialDocument(partial: String): String? = null
