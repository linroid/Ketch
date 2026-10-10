package com.linroid.ketch.core.file

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.isDirectory
import com.linroid.ketch.api.isName
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
class DestinationPathPolicy(roots: List<String>) {
  private val fileSystem = platformFileSystem
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
   * - a bare file name goes through [sanitizeFileName] and is saved in [baseDirectory]
   * - a relative path is resolved against [baseDirectory], and `.` and `..` are resolved
   * - the file name of a file path goes through [sanitizeFileName], and a path that exists or
   *   that a running download reserved gets a free name such as `name (1).ext` instead; a path
   *   naming an existing folder is taken as that folder
   * - a URI is kept as it is
   *
   * @param baseDirectory the folder downloads go to when they name no folder
   * @throws PathRejectedException if the destination lies outside the roots
   */
  fun confine(destination: Destination, baseDirectory: String): Destination =
    when (val confined = confineWithoutFreeName(destination, baseDirectory)) {
      is Confined.As -> confined.destination
      is Confined.File -> {
        val file = OutputPathReservations.freePath(confined.path.toString())
        if (!contains(file)) throw PathRejectedException(destination.value)
        Destination(file)
      }
    }

  /**
   * Whether [confine] made [previous] of [destination] earlier, with the same [baseDirectory]:
   * it would make the same of it now, but for the free name it picked when the file existed or
   * was reserved, such as `name (1).ext`. A request sent again keeps the destination it got the
   * first time this way, although its own download may have created that file since.
   *
   * @throws PathRejectedException if [destination] lies outside the roots
   */
  fun confinesTo(
    destination: Destination,
    baseDirectory: String,
    previous: Destination,
  ): Boolean =
    when (val confined = confineWithoutFreeName(destination, baseDirectory)) {
      is Confined.As -> confined.destination == previous
      is Confined.File -> {
        val path = confined.path
        val earlier = previous.value.toPath()
        earlier == path || earlier.parent == path.parent &&
          FREE_NAME_NUMBER.findAll(earlier.name).any { match ->
            fitFileName(path.name, " (${match.groupValues[1]})") == earlier.name
          }
      }
    }

  /** What [confine] makes of a destination, before it picks a free name for a file. */
  private sealed interface Confined {
    /** The destination as it is saved to. */
    class As(val destination: Destination) : Confined

    /** A file at [path], which gets a free name if it exists or is reserved. */
    class File(val path: Path) : Confined
  }

  private fun confineWithoutFreeName(destination: Destination, baseDirectory: String): Confined {
    val value = destination.value
    requireUsable(value)
    if (isUri(value)) {
      if (!contains(value)) throw PathRejectedException(value)
      return Confined.As(destination)
    }
    if (destination.isName()) {
      if (!contains(baseDirectory)) throw PathRejectedException(baseDirectory)
      return Confined.As(Destination(safeName(value)))
    }
    if (isUri(baseDirectory) && !value.toPath().isAbsolute) throw PathRejectedException(value)
    val path = resolve(value.toPath(), baseDirectory.toPath())
    if (destination.isDirectory() || isDirectory(path)) {
      if (!contains(path)) throw PathRejectedException(value)
      val folder = path.toString().removeSuffix(Path.DIRECTORY_SEPARATOR)
      return Confined.As(Destination(folder + Path.DIRECTORY_SEPARATOR))
    }
    val parent = path.parent ?: throw PathRejectedException(value)
    val file = parent / safeName(path.name)
    if (!contains(file)) throw PathRejectedException(value)
    return Confined.File(file)
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

  private fun safeName(name: String): String =
    sanitizeFileName(name) ?: DefaultFileNameResolver.FALLBACK
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

/** A number in a free name, as `OutputPathReservations` picks it: the `1` of `name (1).ext`. */
private val FREE_NAME_NUMBER = Regex("""\((\d+)\)""")

private fun Path.isWithin(root: Path): Boolean =
  this.root == root.root && segments.size >= root.segments.size &&
    segments.subList(0, root.segments.size) == root.segments
