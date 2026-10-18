package com.linroid.ketch.cli

import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import java.io.PrintStream

/**
 * The terminal line a command's progress is written over, on [out], such as the one of
 * `ketch <url>` or `ketch update`. [show] writes the progress over it and leaves the cursor on it.
 * Every other line goes above it ([printAbove], and the records of a logger made with
 * `Logger.above`), which clears it first and draws it again below them, or below it
 * ([printBelow]), which ends it. Otherwise a log record, written from Ketch's threads to the same
 * terminal, would start at the end of the progress line. One lock keeps the progress and the
 * records from interleaving.
 */
internal class ProgressLine(private val out: PrintStream) {
  private val lock = Any()

  // The progress line on the terminal, `null` while the cursor is at the start of a line.
  private var shown: String? = null

  /** Writes [text] over the progress line, which stays open for the next update. */
  fun show(text: String): Unit = synchronized(lock) {
    // Spaces cover what a longer line left, such as a speed a stall replaced.
    out.print("\r" + text.padEnd(shown?.length ?: 0))
    out.flush()
    shown = text
  }

  /** Prints [text] on a line of its own above the progress line. */
  fun printAbove(text: String): Unit = above { out.println(text) }

  /** Ends the progress line, leaving it on the terminal, and prints [text] below it. */
  fun printBelow(text: String): Unit = synchronized(lock) {
    end()
    out.println(text)
  }

  /** Ends the progress line, leaving it on the terminal, so what follows starts a line below it. */
  fun end(): Unit = synchronized(lock) {
    if (shown != null) {
      out.println()
      shown = null
    }
  }

  /**
   * Runs [block], which writes whole lines to this terminal, on [out] or another stream, with the
   * progress line cleared, then draws the progress line again below them.
   */
  fun <T> above(block: () -> T): T {
    synchronized(lock) {
      val line = shown ?: return block()
      out.print("\r" + " ".repeat(line.length) + "\r")
      // Written before what [block] sends to another stream, such as stderr.
      out.flush()
      try {
        return block()
      } finally {
        out.print(line)
        out.flush()
      }
    }
  }
}

/**
 * This logger, writing each record above [line] ([ProgressLine.above]), for a logger that writes
 * to the terminal [line] is on, such as [Logger.console].
 */
internal fun Logger.above(line: ProgressLine): Logger =
  if (this === Logger.None) this else AboveProgressLogger(this, line)

private class AboveProgressLogger(
  private val delegate: Logger,
  private val line: ProgressLine,
) : Logger {
  override fun v(message: String) = write(LogLevel.VERBOSE) { delegate.v(message) }

  override fun d(message: String) = write(LogLevel.DEBUG) { delegate.d(message) }

  override fun i(message: String) = write(LogLevel.INFO) { delegate.i(message) }

  override fun w(message: String, throwable: Throwable?) =
    write(LogLevel.WARN) { delegate.w(message, throwable) }

  override fun e(message: String, throwable: Throwable?) =
    write(LogLevel.ERROR) { delegate.e(message, throwable) }

  override fun isLoggable(level: LogLevel): Boolean = delegate.isLoggable(level)

  // Records the delegate drops leave the progress line alone.
  private inline fun write(level: LogLevel, crossinline record: () -> Unit) {
    if (delegate.isLoggable(level)) line.above { record() }
  }
}
