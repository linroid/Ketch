package com.linroid.ketch.torrent.metainfo

import com.linroid.ketch.core.file.sanitizeFileName

/**
 * Requires [value], a file or folder name a torrent lists, to stay where it is put: not empty,
 * `.` or `..`, and free of separators and control characters. Any other name opens, through
 * [diskComponent].
 */
internal fun validateTorrentComponent(value: String) {
  require(value.isNotEmpty() && value != "." && value != "..") { "Unsafe torrent path" }
  require(value.none { it < ' ' || it == '/' || it == '\\' }) { "Unsafe torrent path component" }
}

/**
 * Whether [value] can be created as it is on every platform Ketch runs on: at most 255 UTF-8
 * bytes, none of `\/:*?"<>|` or control characters, no trailing `.` or space and no Windows
 * device name.
 */
internal fun isDiskComponent(value: String): Boolean {
  if (value.isEmpty() || value == "." || value == "..") return false
  if (value.encodeToByteArray().size > 255 || value.any { it < ' ' || it in "/\\:*?\"<>|" }) {
    return false
  }
  if (value.endsWith('.') || value.endsWith(' ')) return false
  val stem = value.substringBefore('.').uppercase()
  return stem !in setOf("CON", "PRN", "AUX", "NUL") &&
    !(stem.length == 4 && (stem.startsWith("COM") || stem.startsWith("LPT")) &&
      stem.last() in '1'..'9')
}

/** Requires [value], a name Ketch creates or reads on disk, to be [isDiskComponent]. */
internal fun validateDiskComponent(value: String) {
  require(isDiskComponent(value)) { "Unsafe path component on disk" }
}

/**
 * The name Ketch stores [value], a [validateTorrentComponent] name, under. A name that is
 * already [isDiskComponent] stays as it is, so tasks saved before names were cleaned keep their
 * files; any other goes through [sanitizeFileName]: `<>:"|?*` become `_`, trailing dots and
 * spaces go, a Windows device name gets a leading `_` and a name over 255 bytes is cut.
 */
internal fun diskComponent(value: String): String {
  if (isDiskComponent(value)) return value
  // Only dots, spaces or bidirectional controls leave nothing.
  val cleaned = sanitizeFileName(value) ?: "_"
  check(isDiskComponent(cleaned)) { "Unsafe cleaned torrent path component" }
  return cleaned
}
