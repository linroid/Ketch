package com.linroid.ketch.core.file

/**
 * Joins [directory] and [fileName] into a full output path.
 *
 * On most platforms this is a simple path concatenation. On Android,
 * content URIs require creating a document via the Storage Access
 * Framework, so this function delegates to platform-specific logic.
 */
internal expect fun resolveChildPath(
  directory: String,
  fileName: String,
): String

/**
 * The folder [folderName] inside [directory], such as a category folder. A path is only joined,
 * and the folder is created later with the file in it. On Android, a `content://` tree has the
 * folder found, or else created, as a document, and its document URI is returned, which
 * [resolveChildPath] creates files in.
 */
internal expect fun resolveChildFolder(
  directory: String,
  folderName: String,
): String

/**
 * Whether [directory] is a URI that [resolveChildPath] can create files in
 * on this platform (Android `content://` documents), rather than a path.
 */
internal expect fun isContentUri(directory: String): Boolean
