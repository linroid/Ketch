package com.linroid.ketch.sqlite

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import okio.FileSystem
import okio.IOException
import okio.Path.Companion.toPath
import kotlin.time.Clock
import kotlin.time.Instant

private val log = KetchLogger("SqliteDriver")

/** Files SQLite keeps next to a database, named after it, which move with it. */
private val SIDECAR_SUFFIXES = listOf("-journal", "-wal", "-shm")

/**
 * Moves the database at [path], and the files SQLite keeps next to it, to [brokenPath], and
 * returns where it went. A companion file that cannot be moved is deleted, so SQLite never
 * applies an old journal to the new database.
 *
 * @throws IOException when the database itself cannot be moved.
 */
internal fun moveDatabaseAside(fileSystem: FileSystem, path: String): String {
  val target = brokenPath(path, exists = { fileSystem.exists(it.toPath()) })
  fileSystem.atomicMove(path.toPath(), target.toPath())
  for (suffix in SIDECAR_SUFFIXES) {
    val sidecar = "$path$suffix".toPath()
    if (!fileSystem.exists(sidecar)) continue
    try {
      fileSystem.atomicMove(sidecar, "$target$suffix".toPath())
    } catch (e: IOException) {
      log.w { "Couldn't move $sidecar aside, deleting it: ${e.describeCauses()}" }
      fileSystem.delete(sidecar, mustExist = false)
    }
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
