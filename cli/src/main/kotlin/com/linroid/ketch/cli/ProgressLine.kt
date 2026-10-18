package com.linroid.ketch.cli

import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import java.io.Console
import java.io.PrintStream
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeMark
import kotlin.time.TimeSource

/**
 * The terminal line a command's progress is written over, on [out], such as the one of
 * `ketch <url>` or `ketch update`. [show] writes the progress over it and leaves the cursor on it.
 * Every other line goes above it ([printAbove], and the records of a logger made with
 * `Logger.above`), which clears it first and draws it again below them, or below it
 * ([printBelow]), which ends it. Otherwise a log record, written from Ketch's threads to the same
 * terminal, would start at the end of the progress line. One lock keeps the progress and the
 * records from interleaving.
 *
 * Only an [interactive] terminal is written over. Output captured to a file or a pipe would keep
 * every carriage return, the progress in front of each record, so there the progress goes on
 * lines of its own, at most one every [PLAIN_INTERVAL] by [timeSource], and every other line is
 * printed as it comes. [stdout] counts stdout as a terminal only while stdin is one too
 * ([stdoutIsTerminal]).
 */
internal class ProgressLine(
  private val out: PrintStream,
  private val interactive: Boolean,
  private val timeSource: TimeSource = TimeSource.Monotonic,
) {
  private val lock = Any()

  // The progress line on the terminal, `null` while the cursor is at the start of a line.
  private var shown: String? = null

  // When the progress was last printed on a line of its own, while not interactive.
  private var printedAt: TimeMark? = null

  /** Writes [text] over the progress line, which stays open for the next update. */
  fun show(text: String): Unit = synchronized(lock) {
    if (!interactive) return printLine(text)
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
    out.flush()
  }

  /** Ends the progress line, leaving it on the terminal, so what follows starts a line below it. */
  fun end(): Unit = synchronized(lock) {
    if (shown != null) {
      out.println()
      // Before what follows on another stream, such as an error on stderr.
      out.flush()
      shown = null
    }
  }

  /**
   * Runs [block], which writes whole lines to this terminal, on [out] or another stream, with the
   * progress line cleared, then draws the progress line again below them.
   */
  fun <T> above(block: () -> T): T {
    synchronized(lock) {
      // Never set unless interactive: elsewhere every line is printed as it comes.
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

  /** Prints [text] on a line of its own, unless the last one is younger than [PLAIN_INTERVAL]. */
  private fun printLine(text: String) {
    val last = printedAt
    if (last != null && last.elapsedNow() < PLAIN_INTERVAL) return
    out.println(text)
    out.flush()
    printedAt = timeSource.markNow()
  }

  companion object {
    /** The least time between two lines of progress on output that is not a terminal. */
    val PLAIN_INTERVAL: Duration = 5.seconds

    /**
     * The progress line of stdout, written over only while stdout and stdin are both a terminal
     * ([stdoutIsTerminal]).
     */
    fun stdout(): ProgressLine = ProgressLine(System.out, interactive = stdoutIsTerminal())
  }
}

/**
 * Whether stdout is an interactive terminal. The JVM has a console only while its standard input
 * and output are a terminal; from JDK 22, whose console may also stand for redirected streams,
 * `Console.isTerminal` tells them apart, also checking both. So stdout counts as a terminal only
 * while stdin is one too: a command run in a terminal with its input redirected, as
 * `ketch <url> < /dev/null` or one in a `while read` loop over a file, prints its progress on
 * lines of its own.
 */
internal fun stdoutIsTerminal(): Boolean {
  val console = System.console() ?: return false
  return try {
    Console::class.java.getMethod("isTerminal").invoke(console) as Boolean
  } catch (_: ReflectiveOperationException) {
    // Before JDK 22, which has no such method, a console is a terminal.
    true
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
