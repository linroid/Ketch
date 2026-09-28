package com.linroid.ketch.app.log

import okio.BufferedSink
import okio.FileSystem
import okio.Path
import okio.buffer
import okio.use

/**
 * `ketch.log` in [directory], moved aside to `ketch.1.log` once the next record would take it
 * past [maxFileBytes]. Older files shift to `ketch.2.log` and so on; the oldest is deleted so
 * that at most [maxFiles] files remain. Appending continues an existing `ketch.log`, so the
 * logs of earlier runs are kept.
 *
 * Not thread-safe: [FileLogger] uses it from a single writer.
 */
internal class RotatingLogFile(
  private val fileSystem: FileSystem,
  private val directory: Path,
  private val maxFileBytes: Long,
  private val maxFiles: Int,
) {
  init {
    require(maxFileBytes > 0) { "maxFileBytes must be positive: $maxFileBytes" }
    require(maxFiles > 0) { "maxFiles must be positive: $maxFiles" }
  }

  /** The current file first, then its predecessors from newest to oldest. */
  private val names: List<Path> =
    listOf(directory / LOG_FILE_NAME) + (1 until maxFiles).map { directory / "ketch.$it.log" }

  private var sink: BufferedSink? = null
  private var size = 0L

  /** Existing log files, oldest first. */
  fun files(): List<Path> = names.reversed().filter { fileSystem.exists(it) }

  /**
   * Appends [record] and a line break. A record is never split: one larger than
   * [maxFileBytes] gets a file of its own.
   */
  fun append(record: String) {
    val bytes = (record + "\n").encodeToByteArray()
    var out = sink ?: open()
    if (size > 0 && size + bytes.size > maxFileBytes) {
      rotate()
      out = open()
    }
    out.write(bytes)
    size += bytes.size
  }

  /** Writes buffered records to disk. */
  fun flush() {
    sink?.flush()
  }

  /** Replaces [target] with every log file joined together, oldest first. */
  fun copyTo(target: Path) {
    flush()
    target.parent?.let { fileSystem.createDirectories(it) }
    fileSystem.write(target) {
      files().forEach { file -> fileSystem.source(file).use { writeAll(it) } }
    }
  }

  /** Closes the current file; the next [append] opens it again. */
  fun close() {
    val closing = sink ?: return
    sink = null
    closing.close()
  }

  private fun open(): BufferedSink {
    fileSystem.createDirectories(directory)
    size = fileSystem.metadataOrNull(names.first())?.size ?: 0L
    return fileSystem.appendingSink(names.first()).buffer().also { sink = it }
  }

  private fun rotate() {
    close()
    fileSystem.delete(names.last())
    // Oldest first, so every move lands on a name that was just freed.
    for (index in names.lastIndex downTo 1) {
      val from = names[index - 1]
      if (fileSystem.exists(from)) fileSystem.atomicMove(from, names[index])
    }
  }

  companion object {
    /** Name of the file being written; rotated files are numbered before the extension. */
    const val LOG_FILE_NAME = "ketch.log"
  }
}
