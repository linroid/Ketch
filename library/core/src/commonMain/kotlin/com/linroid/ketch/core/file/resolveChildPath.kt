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
 * Whether [directory] is a URI that [resolveChildPath] can create files in
 * on this platform (Android `content://` documents), rather than a path.
 */
internal expect fun isContentUri(directory: String): Boolean
