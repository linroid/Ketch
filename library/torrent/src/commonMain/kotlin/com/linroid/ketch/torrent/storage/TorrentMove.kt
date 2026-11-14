package com.linroid.ketch.torrent.storage

import okio.FileSystem
import okio.IOException
import okio.Path

/**
 * Moves the file or folder [source] to [target], which must not exist: by renaming it, or
 * where that fails, as between volumes, by copying it and then deleting the original. A copy
 * that fails is deleted again, leaving [source] as it was.
 */
internal fun moveTorrentTree(files: FileSystem, source: Path, target: Path) {
  check(files.metadataOrNull(target) == null) { "The folder already has something of that name" }
  try {
    files.atomicMove(source, target)
    return
  } catch (_: IOException) {
    // Not on one volume: copied below.
  }
  try {
    val below = if (files.metadata(source).isDirectory) files.listRecursively(source) else
      emptySequence()
    val paths = sequenceOf(source) + below
    for (path in paths) {
      val copy = if (path == source) target else target / path.relativeTo(source)
      val info = files.metadata(path)
      when {
        info.symlinkTarget != null -> throw IOException("Cannot move a link")
        info.isDirectory -> files.createDirectories(copy)
        else -> files.copy(path, copy)
      }
    }
  } catch (e: IOException) {
    files.deleteRecursively(target, mustExist = false)
    throw e
  }
  files.deleteRecursively(source, mustExist = false)
}
