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
 */
@OptIn(ExperimentalAtomicApi::class)
internal object OutputPathReservations {
  // Normalized path to the number of downloads holding it: a file destination is used as it is,
  // by as many downloads as name it.
  private val reserved = AtomicReference(emptyMap<String, Int>())

  /** Reserves [path], used as it is, until [release]. */
  fun reserve(path: String) {
    val key = key(path.toPath())
    update { it + (key to (it[key] ?: 0) + 1) }
  }

  /**
   * Reserves and returns [candidate], or when it exists or is reserved, the first free
   * `name (n).ext` beside it.
   */
  fun reserveUnique(candidate: String): String {
    while (true) {
      val current = reserved.load()
      val path = firstFree(candidate.toPath(), current)
      if (reserved.compareAndSet(current, current + (key(path) to 1))) return path.toString()
    }
  }

  /** Releases one reservation of [path]. */
  fun release(path: String) {
    val key = key(path.toPath())
    update {
      val count = it[key] ?: return@update it
      if (count > 1) it + (key to count - 1) else it - key
    }
  }

  private fun firstFree(candidate: Path, reserved: Map<String, Int>): Path {
    fun isFree(path: Path) = key(path) !in reserved && !platformFileSystem.exists(path)
    if (isFree(candidate)) return candidate
    val directory = candidate.parent ?: return candidate
    var seq = 1
    while (true) {
      val path = directory / fitFileName(candidate.name, " ($seq)")
      if (isFree(path)) return path
      seq++
    }
  }

  private fun key(path: Path): String = path.normalized().toString()

  private inline fun update(transform: (Map<String, Int>) -> Map<String, Int>) {
    while (true) {
      val current = reserved.load()
      if (reserved.compareAndSet(current, transform(current))) return
    }
  }
}
