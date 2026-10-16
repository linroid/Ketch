package com.linroid.ketch.core.file

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import okio.Path
import okio.Path.Companion.toPath

/**
 * Output paths that running downloads write to. A download creates its file when it writes its
 * first bytes, a while after its name was chosen, so two downloads given the same name at nearly
 * the same time would otherwise both find the path free and write into one file. Shared by the
 * whole process, so [com.linroid.ketch.core.Ketch] instances saving to one folder avoid each
 * other too.
 *
 * A reservation covers both names of a download: the final one and the partial one it writes
 * under until it completes ([partialPathFor]). A path is free only when neither of its names
 * exists or is reserved, so a paused download's partial file keeps its final name taken.
 */
@OptIn(ExperimentalAtomicApi::class)
internal object OutputPathReservations {
  // Normalized path to the number of downloads holding it: a file destination is used as it is,
  // by as many downloads as name it.
  private val reserved = AtomicReference(emptyMap<String, Int>())

  /** Reserves [path], used as it is, until [release]. */
  fun reserve(path: String) {
    val keys = keys(path.toPath())
    update { current -> keys.fold(current) { map, key -> map + (key to (map[key] ?: 0) + 1) } }
  }

  /**
   * Reserves and returns [candidate], or when it exists or is reserved, the first free
   * `name (n).ext` beside it.
   */
  fun reserveUnique(candidate: String): String {
    while (true) {
      val current = reserved.load()
      val path = firstFree(candidate.toPath(), current)
      val next = keys(path).fold(current) { map, key -> map + (key to (map[key] ?: 0) + 1) }
      if (reserved.compareAndSet(current, next)) return path.toString()
    }
  }

  /**
   * [candidate], or when it exists or is reserved, the first free `name (n).ext` beside it,
   * without reserving it.
   */
  fun freePath(candidate: String): String =
    firstFree(candidate.toPath(), reserved.load()).toString()

  /** Releases one reservation of [path]. */
  fun release(path: String) {
    val keys = keys(path.toPath())
    update { current ->
      keys.fold(current) { map, key ->
        val count = map[key] ?: return@fold map
        if (count > 1) map + (key to count - 1) else map - key
      }
    }
  }

  private fun firstFree(candidate: Path, reserved: Map<String, Int>): Path {
    fun isFree(path: Path): Boolean {
      val partial = partialPathFor(path)?.toPath()
      return keys(path).none { it in reserved } && !platformFileSystem.exists(path) &&
        (partial == null || !platformFileSystem.exists(partial))
    }
    if (isFree(candidate)) return candidate
    val directory = candidate.parent ?: return candidate
    var seq = 1
    while (true) {
      val path = directory / fitFileName(candidate.name, " ($seq)")
      if (isFree(path)) return path
      seq++
    }
  }

  /** The keys of [path]: its own and its partial file's. */
  private fun keys(path: Path): List<String> =
    listOfNotNull(key(path), partialPathFor(path)?.let { key(it.toPath()) })

  private fun key(path: Path): String = path.normalized().toString()

  private inline fun update(transform: (Map<String, Int>) -> Map<String, Int>) {
    while (true) {
      val current = reserved.load()
      if (reserved.compareAndSet(current, transform(current))) return
    }
  }
}
