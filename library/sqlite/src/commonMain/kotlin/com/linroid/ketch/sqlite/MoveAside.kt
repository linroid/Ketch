package com.linroid.ketch.sqlite

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import okio.FileSystem
import okio.IOException
import okio.Path
import okio.Path.Companion.toPath
import kotlin.time.Clock
import kotlin.time.Instant

private val log = KetchLogger("SqliteDriver")

/** Files SQLite keeps next to a database, named after it, which move with it. */
private val SIDECAR_SUFFIXES = listOf("-journal", "-wal", "-shm")

/**
 * Moves the database at [path], and the files SQLite keeps next to it, to [brokenPath] together,
 * and returns where they went. They move all or none: a journal left behind would be applied to
 * the new database, and a database moved without being reported would hide its tasks.
 *
 * @throws IOException when one of them cannot be moved, after putting back those that had.
 */
internal fun moveDatabaseAside(fileSystem: FileSystem, path: String): String {
  val target = brokenPath(path, exists = { fileSystem.exists(it.toPath()) })
  val moved = mutableListOf<Pair<Path, Path>>()
  try {
    for (suffix in listOf("") + SIDECAR_SUFFIXES) {
      val source = "$path$suffix".toPath()
      if (suffix.isNotEmpty() && !fileSystem.exists(source)) continue
      val destination = "$target$suffix".toPath()
      fileSystem.atomicMove(source, destination)
      moved += source to destination
    }
  } catch (e: IOException) {
    for ((source, destination) in moved.asReversed()) {
      try {
        fileSystem.atomicMove(destination, source)
      } catch (putBack: IOException) {
        log.w { "Couldn't put $destination back to $source: ${putBack.describeCauses()}" }
        e.addSuppressed(putBack)
      }
    }
    throw e
  }
  return target
}

/**
 * Where an unreadable file at [path] is moved: `<path>.broken-<UTC time>`, such as
 * `ketch.db.broken-20261003T142530Z`, numbered when a file of that name [exists].
 */
internal fun brokenPath(
  path: String,
  exists: (String) -> Boolean,
  now: Instant = Clock.System.now(),
): String {
  // ISO 8601 basic format, without the ':' that Windows refuses in file names.
  val stamp = Instant.fromEpochSeconds(now.epochSeconds).toString()
    .filter { it != '-' && it != ':' }
  val base = "$path.broken-$stamp"
  return generateSequence(1) { it + 1 }
    .map { if (it == 1) base else "$base-$it" }
    .first { !exists(it) }
}
