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

/** The type of a document whose name has no extension that maps to one. */
internal const val DEFAULT_DOCUMENT_MIME_TYPE = "application/octet-stream"

/**
 * The MIME type to create a document named [fileName] with in an Android `content://` folder:
 * the one [mimeTypeOfExtension] maps the text after the name's last dot to, else
 * [DEFAULT_DOCUMENT_MIME_TYPE].
 *
 * Android's document providers split a new document's name into a base name and an extension
 * only when the type asked for is the one they map that extension to, and otherwise take the
 * whole name as the base name. A name that is taken then gets its ` (n)` at the very end,
 * `abc.apk (1)`, rather than before the extension, `abc (1).apk`. Asking for the type they
 * derive themselves keeps the extension last, also for extensions they do not know.
 *
 * @param mimeTypeOfExtension the provider's own lookup, given the extension as the name has it
 */
internal fun documentMimeType(
  fileName: String,
  mimeTypeOfExtension: (String) -> String?,
): String {
  val lastDot = fileName.lastIndexOf('.')
  if (lastDot < 0) return DEFAULT_DOCUMENT_MIME_TYPE
  return mimeTypeOfExtension(fileName.substring(lastDot + 1)) ?: DEFAULT_DOCUMENT_MIME_TYPE
}
