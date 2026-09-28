package com.linroid.ketch.app.log

import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.api.log.formatLogLine
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.completeWith
import kotlinx.coroutines.launch
import okio.FileSystem
import okio.Path

/**
 * [Logger] that keeps the app's logs in [directory] so they can be attached to bug reports.
 * Records are written to `ketch.log` in the same format as [Logger.console]; once the file
 * reaches [maxFileBytes] it is rotated, keeping at most [maxFiles] files.
 *
 * Logging only formats and queues a record, so it never waits for the disk. A single
 * coroutine on [dispatcher] writes records in order and flushes whenever the queue runs
 * empty. A record that cannot be written, for example because the disk is full, is dropped.
 *
 * @param minLevel records below this level are discarded
 */
class FileLogger(
  fileSystem: FileSystem,
  val directory: Path,
  dispatcher: CoroutineDispatcher,
  private val minLevel: LogLevel = LogLevel.DEBUG,
  maxFileBytes: Long = DEFAULT_MAX_FILE_BYTES,
  maxFiles: Int = DEFAULT_MAX_FILES,
) : Logger {
  private val file = RotatingLogFile(fileSystem, directory, maxFileBytes, maxFiles)
  private val jobs = Channel<(RotatingLogFile) -> Unit>(Channel.UNLIMITED)
  private val writer = CoroutineScope(dispatcher).launch {
    try {
      while (true) {
        var job = jobs.receiveCatching().getOrNull() ?: break
        // Everything already queued is written before the flush, so a burst costs one write.
        while (true) {
          runJob(job)
          job = jobs.tryReceive().getOrNull() ?: break
        }
        runJob { it.flush() }
      }
    } finally {
      runCatching { file.close() }
    }
  }

  override fun v(message: String) = log(LogLevel.VERBOSE, message, null)

  override fun d(message: String) = log(LogLevel.DEBUG, message, null)

  override fun i(message: String) = log(LogLevel.INFO, message, null)

  override fun w(message: String, throwable: Throwable?) = log(LogLevel.WARN, message, throwable)

  override fun e(message: String, throwable: Throwable?) = log(LogLevel.ERROR, message, throwable)

  /** Waits until every record logged so far is on disk. */
  suspend fun flush() = onWriter { it.flush() }

  /**
   * Replaces [target] with all log files joined together, oldest first, including every record
   * logged so far. Sharing this copy is safe while logging goes on.
   */
  suspend fun exportTo(target: Path) = onWriter { it.copyTo(target) }

  /** Writes the queued records and closes the file; later records are discarded. */
  suspend fun close() {
    jobs.close()
    writer.join()
  }

  private fun log(level: LogLevel, message: String, throwable: Throwable?) {
    if (level < minLevel) return
    val record = formatLogLine(level, message, throwable)
    jobs.trySend { it.append(record) }
  }

  /**
   * Runs [job] on the writer. If the file fails, records not yet on disk are dropped and the next
   * job reopens the file.
   */
  private fun runJob(job: (RotatingLogFile) -> Unit) {
    try {
      job(file)
    } catch (_: Exception) {
      runCatching { file.close() }
    }
  }

  /** Runs [action] on the writer after the records queued before it. */
  private suspend fun onWriter(action: (RotatingLogFile) -> Unit) {
    val done = CompletableDeferred<Unit>()
    jobs.send { file -> done.completeWith(runCatching { action(file) }) }
    done.await()
  }

  companion object {
    /** Size at which `ketch.log` is rotated: 5 MiB. */
    const val DEFAULT_MAX_FILE_BYTES: Long = 5L * 1024 * 1024

    /** `ketch.log` and two rotated files. */
    const val DEFAULT_MAX_FILES: Int = 3
  }
}
