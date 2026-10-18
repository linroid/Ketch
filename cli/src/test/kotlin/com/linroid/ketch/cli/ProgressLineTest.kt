package com.linroid.ketch.cli

import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import kotlin.test.Test
import kotlin.test.assertEquals

class ProgressLineTest {
  // One terminal, which stdout and stderr both write to, each through a buffer of its own.
  private val terminal = ByteArrayOutputStream()
  private val out = PrintStream(BufferedOutputStream(terminal), false, Charsets.UTF_8)
  private val err = PrintStream(BufferedOutputStream(terminal), false, Charsets.UTF_8)
  private val line = ProgressLine(out)

  @Test
  fun above_recordWhileTheLineIsShown_takesARowOfItsOwnAboveTheLine() {
    val logger = PrintingLogger(LogLevel.WARN).above(line)
    line.show("[Downloading] 50%  1.0 MB / 2.0 MB  1.0 MB/s")

    logger.w("[WARN] [Tracker] Tracker warns about 08ada5a7a618")
    logger.e("[ERROR] [Execution] Couldn't save the piece")

    assertEquals(
      listOf(
        "[WARN] [Tracker] Tracker warns about 08ada5a7a618",
        "[ERROR] [Execution] Couldn't save the piece",
        "[Downloading] 50%  1.0 MB / 2.0 MB  1.0 MB/s",
      ),
      screen(),
    )
  }

  @Test
  fun above_recordTheLoggerDrops_leavesTheLineAlone() {
    val logger = PrintingLogger(LogLevel.WARN).above(line)
    line.show("[Downloading] 50%")
    val shown = terminal.toByteArray()

    logger.d("[DEBUG] [Execution] Segment done")
    out.flush()

    assertEquals(shown.decodeToString(), terminal.toByteArray().decodeToString())
  }

  @Test
  fun show_shorterLine_coversWhatTheLongerOneLeft() {
    line.show("[Downloading] 50%  1.0 MB / 2.0 MB  stalled 2m 30s")
    line.show("[Downloading] 50%  1.0 MB / 2.0 MB")

    assertEquals(listOf("[Downloading] 50%  1.0 MB / 2.0 MB"), screen())
  }

  @Test
  fun printBelow_lineShown_keepsItAndStartsTheNextRow() {
    line.show("[Downloading] 100%  2.0 MB / 2.0 MB")
    line.printBelow("[Completed] Saved to /tmp/a.bin")
    line.printBelow("Download completed: /tmp/a.bin")

    assertEquals(
      listOf(
        "[Downloading] 100%  2.0 MB / 2.0 MB",
        "[Completed] Saved to /tmp/a.bin",
        "Download completed: /tmp/a.bin",
      ),
      screen(),
    )
  }

  /** The rows [terminal] shows, empty last one left out. */
  private fun screen(): List<String> {
    out.flush()
    err.flush()
    return terminalRows(terminal.toString(Charsets.UTF_8)).dropLastWhile { it.isEmpty() }
  }

  /** Prints records from [minLevel] as they come, errors to [err] and the rest to [out]. */
  private inner class PrintingLogger(private val minLevel: LogLevel) : Logger {
    override fun v(message: String) = print(LogLevel.VERBOSE, message)

    override fun d(message: String) = print(LogLevel.DEBUG, message)

    override fun i(message: String) = print(LogLevel.INFO, message)

    override fun w(message: String, throwable: Throwable?) = print(LogLevel.WARN, message)

    override fun e(message: String, throwable: Throwable?) = print(LogLevel.ERROR, message)

    override fun isLoggable(level: LogLevel): Boolean = level >= minLevel

    private fun print(level: LogLevel, message: String) {
      if (!isLoggable(level)) return
      val stream = if (level == LogLevel.ERROR) err else out
      stream.println(message)
      stream.flush()
    }
  }
}

/**
 * The rows a terminal shows after [output], trailing spaces left out: `\r` goes back to the start
 * of the row, and what follows writes over it.
 */
internal fun terminalRows(output: String): List<String> {
  val rows = mutableListOf(StringBuilder())
  var column = 0
  for (char in output) {
    when (char) {
      '\n' -> {
        rows += StringBuilder()
        column = 0
      }
      '\r' -> column = 0
      else -> {
        val row = rows.last()
        if (column < row.length) row.setCharAt(column, char) else row.append(char)
        column++
      }
    }
  }
  return rows.map { it.toString().trimEnd() }
}
