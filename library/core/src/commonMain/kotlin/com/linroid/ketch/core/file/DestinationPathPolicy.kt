package com.linroid.ketch.core.file

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.isDirectory
import com.linroid.ketch.api.isName
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath

/**
 * Keeps the paths that callers on other devices choose inside a set of root folders, such as
 * the download directory, so they cannot write or delete files elsewhere. In-process callers may
 * save anywhere and do not need it; the daemon server confines its API's callers with it.
 *
 * A path is inside a root when it is the root or lies below it, after `.` and `..` are resolved
 * and symbolic links are followed in the part of the path that exists: a link inside a root
 * counts as the place it points to, and a link whose target is missing is refused. URIs, such as
 * an Android `content://` tree, are compared as text: one is inside a root URI when it equals it
 * or continues it after a `/`.
 *
 * @param roots folders, as absolute paths or URIs; relative paths are resolved against the
 *   current directory
 */
class DestinationPathPolicy internal constructor(
  roots: List<String>,
  private val fileSystem: FileSystem,
) {
  constructor(roots: List<String>) : this(roots, platformFileSystem)

  private val currentDirectory: Path by lazy { fileSystem.canonicalize(".".toPath()) }
  private val rootUris = roots.filter(::isUri).map { it.trimEnd('/') }
  private val rootPaths = roots.filterNot(::isUri).mapNotNull { realPath(it.toPath()) }

  /** Whether [path], a file or folder as a path or URI, is one of the roots or inside one. */
  fun contains(path: String): Boolean {
    if (isUri(path)) return rootUris.any { path == it || path.startsWith("$it/") }
    return contains(path.toPath())
  }

  private fun contains(path: Path): Boolean {
    val real = realPath(path) ?: return false
    return rootPaths.any { real.isWithin(it) }
  }

  /**
   * [destination] of a new download, made safe to save in:
   * - a bare file name is [sanitized][sanitizeFileName] and saved in [baseDirectory]
   * - a relative path is resolved against [baseDirectory], and `.` and `..` are resolved
   * - the file name of a file path is sanitized, and an existing file gets a free name such as
   *   `name (1).ext` instead of being overwritten; a path naming an existing folder is taken as
   *   that folder
   * - a URI is kept as it is
   *
   * @param baseDirectory the folder downloads go to when they name no folder
   * @throws PathRejectedException if the destination lies outside the roots
   */
  fun confine(destination: Destination, baseDirectory: String): Destination {
    val value = destination.value
    requireUsable(value)
    if (isUri(value)) {
      if (!contains(value)) throw PathRejectedException(value)
      return destination
    }
    if (destination.isName()) {
      if (!contains(baseDirectory)) throw PathRejectedException(baseDirectory)
      return Destination(sanitizeFileName(value))
    }
    if (isUri(baseDirectory) && !value.toPath().isAbsolute) throw PathRejectedException(value)
    val path = resolve(value.toPath(), baseDirectory.toPath())
    if (destination.isDirectory() || isDirectory(path)) {
      if (!contains(path)) throw PathRejectedException(value)
      val folder = path.toString().removeSuffix(Path.DIRECTORY_SEPARATOR)
      return Destination(folder + Path.DIRECTORY_SEPARATOR)
    }
    val parent = path.parent ?: throw PathRejectedException(value)
    val file = deduplicate(parent / sanitizeFileName(path.name), fileSystem)
    if (!contains(file)) throw PathRejectedException(value)
    return Destination(file.toString())
  }

  /**
   * [path], the file an existing download continues in, such as the destination of a resume:
   * an absolute file path, with `.` and `..` resolved, or a URI.
   *
   * @throws PathRejectedException if [path] is relative, names a folder, or lies outside the
   *   roots
   */
  fun confineFile(path: String): String {
    requireUsable(path)
    if (isUri(path)) {
      if (!contains(path)) throw PathRejectedException(path)
      return path
    }
    val file = path.toPath()
    if (!file.isAbsolute) throw PathRejectedException(path, "must be an absolute path")
    val normalized = file.normalized()
    if (normalized.parent == null || isDirectory(normalized)) {
      throw PathRejectedException(path, "must name a file")
    }
    if (!contains(normalized)) throw PathRejectedException(path)
    return normalized.toString()
  }

  private fun requireUsable(value: String) {
    if (value.isBlank() || '\u0000' in value) throw PathRejectedException(value, "is not a path")
  }

  private fun resolve(path: Path, base: Path): Path =
    (if (path.isAbsolute) path else base / path).normalized()

  /**
   * [path] with symbolic links followed in the part of it that exists, or `null` when that part
   * ends in a link whose target is missing.
   */
  private fun realPath(path: Path): Path? {
    var existing = (if (path.isAbsolute) path else currentDirectory / path).normalized()
    val missing = ArrayDeque<String>()
    while (true) {
      try {
        return missing.fold(fileSystem.canonicalize(existing)) { real, name -> real / name }
      } catch (_: IOException) {
        // A link to a missing target exists but cannot be resolved; writing through it would
        // create its target, wherever that is.
        if (exists(existing)) return null
        val parent = existing.parent ?: return null
        missing.addFirst(existing.name)
        existing = parent
      }
    }
  }

  /** Whether [path] exists; `true` when that cannot be told, so callers refuse it. */
  private fun exists(path: Path): Boolean = try {
    fileSystem.metadataOrNull(path) != null
  } catch (_: IOException) {
    true
  }

  private fun isDirectory(path: Path): Boolean = try {
    fileSystem.metadataOrNull(path)?.isDirectory == true
  } catch (_: IOException) {
    false
  }

  companion object {
    /** Name given to a file whose name has nothing left once sanitized. */
    private const val FALLBACK_NAME = "download"

    /** Longest file name most file systems store, in UTF-8 bytes. */
    private const val MAX_NAME_BYTES = 255

    /** Longest extension kept when a long name is shortened, with its dot. */
    private const val MAX_EXTENSION_LENGTH = 16

    /** Characters no Windows file name may hold, which also include both path separators. */
    private const val UNSAFE_CHARS = "/\\:*?\"<>|"

    private val WINDOWS_RESERVED = setOf("CON", "PRN", "AUX", "NUL") +
      (0..9).map { "COM$it" } + (0..9).map { "LPT$it" }

    /**
     * [name] as a single file name every platform can store: path separators, characters
     * Windows forbids (`: * ? " < > |`) and control characters become `_`; surrounding spaces
     * and trailing dots go, which also turns `.` and `..` into the fallback `download`; a
     * Windows device name such as `CON` gets a `_`; and a name longer than 255 UTF-8 bytes is
     * shortened, keeping its extension.
     */
    fun sanitizeFileName(name: String): String {
      val replaced = buildString(name.length) {
        for (char in name) append(if (char in UNSAFE_CHARS || char.isISOControl()) '_' else char)
      }
      // Windows drops trailing dots and spaces, so `name.` would open `name`.
      val trimmed = replaced.trim().trimEnd('.', ' ')
      if (trimmed.isEmpty()) return FALLBACK_NAME
      val stem = trimmed.substringBefore('.')
      val safe = if (stem.uppercase() in WINDOWS_RESERVED) {
        stem + "_" + trimmed.removePrefix(stem)
      } else {
        trimmed
      }
      return shortened(safe)
    }

    private fun shortened(name: String): String {
      if (name.encodeToByteArray().size <= MAX_NAME_BYTES) return name
      val dot = name.lastIndexOf('.')
      val extension = if (dot > 0 && name.length - dot <= MAX_EXTENSION_LENGTH) {
        name.substring(dot)
      } else {
        ""
      }
      val budget = MAX_NAME_BYTES - extension.encodeToByteArray().size
      // Every character takes a byte at least, so the stem never needs more characters.
      var stem = name.substring(0, name.length - extension.length).take(budget)
      if (stem.last().isHighSurrogate()) stem = stem.dropLast(1)
      while (stem.encodeToByteArray().size > budget) {
        stem = stem.dropLast(if (stem.length > 1 && stem.last().isLowSurrogate()) 2 else 1)
      }
      return stem.trimEnd('.', ' ').ifEmpty { FALLBACK_NAME } + extension
    }

    /**
     * [candidate], or when it exists, the first free `name (1).ext`, `name (2).ext`, … beside
     * it.
     */
    internal fun deduplicate(candidate: Path, fileSystem: FileSystem = platformFileSystem): Path {
      val fileName = candidate.name
      val directory = candidate.parent ?: return candidate
      if (!fileSystem.exists(candidate)) return candidate

      val dotIndex = fileName.lastIndexOf('.')
      val baseName: String
      val extension: String
      if (dotIndex > 0) {
        baseName = fileName.take(dotIndex)
        extension = fileName.substring(dotIndex)
      } else {
        baseName = fileName
        extension = ""
      }

      var seq = 1
      while (true) {
        val path = directory / "$baseName ($seq)$extension"
        if (!fileSystem.exists(path)) return path
        seq++
      }
    }
  }
}

/**
 * Thrown when a path that a caller on another device chose lies outside the folders a
 * [DestinationPathPolicy] allows, or is not a usable path at all.
 *
 * @property path the path as the caller gave it
 */
class PathRejectedException(
  val path: String,
  reason: String = "is outside the folders this device saves downloads to",
) : IllegalArgumentException("$path $reason")

private fun isUri(value: String): Boolean = "://" in value

private fun Path.isWithin(root: Path): Boolean =
  this.root == root.root && segments.size >= root.segments.size &&
    segments.subList(0, root.segments.size) == root.segments
