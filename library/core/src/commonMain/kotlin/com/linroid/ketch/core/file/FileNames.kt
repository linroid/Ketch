package com.linroid.ketch.core.file

import okio.Path.Companion.toPath

/** Longest file name, in UTF-8 bytes, that common file systems accept. */
internal const val MAX_FILE_NAME_BYTES = 255

/** Characters Windows does not allow in a file name; the separators are removed before. */
private const val WINDOWS_RESERVED_CHARS = "<>:\"|?*"

/** Unicode `Bidi_Control` characters, which can make `exe.txt` read as `txt.exe`. */
private const val BIDI_CONTROLS =
  "\u061C\u200E\u200F\u202A\u202B\u202C\u202D\u202E\u2066\u2067\u2068\u2069"

/**
 * Makes [name], a file name that a server or a link suggests, safe to create inside a download
 * folder. A [com.linroid.ketch.core.engine.DownloadSource] passes names it reads from a response
 * or a URL through it before returning them as
 * [com.linroid.ketch.api.ResolvedSource.suggestedFileName]; the engine applies it again before
 * it joins such a name to the folder.
 *
 * - Only the last path segment is kept, splitting on both `/` and `\`, so the name cannot leave
 *   the folder or name an absolute path
 * - Control characters and bidirectional controls are removed
 * - Characters Windows does not allow (`<>:"|?*`) become `_`, and a name Windows reserves for a
 *   device (`CON`, `NUL.txt`, `COM1`, ...) gets a leading `_`
 * - Leading whitespace and trailing whitespace and dots are trimmed, so `.` and `..` leave nothing
 * - A name longer than 255 UTF-8 bytes is cut, keeping its extension
 *
 * @return the safe name, or `null` when nothing usable is left, so the caller can fall back to
 *   another name
 */
fun sanitizeFileName(name: String): String? {
  val baseName = name.substring(name.lastIndexOfAny(charArrayOf('/', '\\')) + 1)
  val cleaned = buildString {
    for (c in baseName) {
      when {
        c.isISOControl() || c in BIDI_CONTROLS -> Unit
        c in WINDOWS_RESERVED_CHARS -> append('_')
        else -> append(c)
      }
    }
  }.trimFileName()
  if (cleaned.isEmpty()) return null
  // Checked once cut: cutting `CONX.<long extension>` leaves `CON.<long extension>`.
  val fitted = fitFileName(cleaned).trimFileName()
  val safe = if (isWindowsDeviceName(fitted)) fitFileName("_$fitted").trimFileName() else fitted
  return safe.ifEmpty { null }
}

/**
 * [name] with [suffix] added before its extension, its stem cut at a character boundary so the
 * result fits in [MAX_FILE_NAME_BYTES] UTF-8 bytes. An extension that leaves no room for the stem
 * is cut along with it.
 */
internal fun fitFileName(name: String, suffix: String = ""): String {
  val dotIndex = name.lastIndexOf('.')
  val keptExtension = if (dotIndex > 0) name.substring(dotIndex) else ""
  val extension = keptExtension.takeIf { utf8Size(it + suffix) < MAX_FILE_NAME_BYTES }.orEmpty()
  val stem = name.dropLast(extension.length)
  val room = MAX_FILE_NAME_BYTES - utf8Size(suffix + extension)
  return stem.takeUtf8Bytes(room) + suffix + extension
}

/**
 * Whether [path] names an entry inside [directory], comparing both after resolving `.` and `..`
 * segments. Paths with different roots, such as an absolute path joined to a relative folder,
 * never are.
 */
internal fun isInsideDirectory(directory: String, path: String): Boolean {
  val dir = directory.toPath(normalize = true)
  val child = path.toPath(normalize = true)
  if (child.root != dir.root) return false
  val dirSegments = if (dir == ".".toPath()) emptyList() else dir.segments
  val childSegments = child.segments
  // A normalized relative path keeps its `..` segments at the start, so only the first segment
  // after the folder's can climb out of it.
  return childSegments.size > dirSegments.size &&
    childSegments.subList(0, dirSegments.size) == dirSegments &&
    childSegments[dirSegments.size] != ".."
}

private fun String.trimFileName(): String =
  trimStart().trimEnd { it == '.' || it.isWhitespace() }

private fun isWindowsDeviceName(name: String): Boolean {
  val stem = name.substringBefore('.').trimEnd().uppercase()
  return stem in setOf("CON", "PRN", "AUX", "NUL", "CONIN$", "CONOUT$") ||
    stem.length == 4 && (stem.startsWith("COM") || stem.startsWith("LPT")) &&
    stem.last() in "0123456789¹²³"
}

private fun utf8Size(value: String): Int = value.encodeToByteArray().size

/** The longest prefix of this string that fits in [maxBytes] UTF-8 bytes, whole characters only. */
private fun String.takeUtf8Bytes(maxBytes: Int): String {
  var bytes = 0
  var end = 0
  while (end < length) {
    val c = this[end]
    val pair = c.isHighSurrogate() && end + 1 < length && this[end + 1].isLowSurrogate()
    val size = when {
      pair -> 4
      c.code < 0x80 -> 1
      c.code < 0x800 -> 2
      else -> 3
    }
    if (bytes + size > maxBytes) break
    bytes += size
    end += if (pair) 2 else 1
  }
  return substring(0, end)
}
