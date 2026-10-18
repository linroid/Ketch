package com.linroid.ketch.cli

import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.util.concurrent.CyclicBarrier
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TestTimeSource

class ProgressLineTest {
  // One terminal, which stdout and stderr both write to, each through a buffer of its own.
  private val terminal = ByteArrayOutputStream()
  private val out = PrintStream(BufferedOutputStream(terminal), false, Charsets.UTF_8)
  private val err = PrintStream(BufferedOutputStream(terminal), false, Charsets.UTF_8)
  private val line = ProgressLine(out, interactive = true)

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

  @Test
  fun above_recordsFromOtherThreadsWhileTheLineIsUpdated_eachTakeARowOfTheirOwn() {
    // Ketch logs from its own threads while the command's thread writes the progress: a record
    // written between an update and the line it covers would land in the middle of the line.
    val logger = PrintingLogger(LogLevel.WARN).above(line)
    val start = CyclicBarrier(LOGGING_THREADS + 1)
    val progress = thread {
      start.await()
      // Lines of different lengths, so one left behind shows.
      repeat(UPDATES) { line.show("[Downloading] ${it % 100}%" + " #".repeat(it % 5)) }
    }
    val loggers = (1..LOGGING_THREADS).map { id ->
      thread {
        start.await()
        repeat(RECORDS) {
          if (it % 4 == 0) {
            logger.e("[ERROR] [Thread $id] record $it")
          } else {
            logger.w("[WARN] [Thread $id] record $it")
          }
        }
      }
    }
    (loggers + progress).forEach { it.join() }

    val rows = screen()
    val records = rows.dropLast(1)
    val record = Regex("""\[(WARN|ERROR)] \[Thread \d] record \d+""")
    assertEquals(emptyList(), records.filterNot { record.matches(it) })
    // Each once.
    assertEquals(LOGGING_THREADS * RECORDS, records.size)
    assertEquals(records.size, records.toSet().size)
    assertTrue(Regex("""\[Downloading] \d+%( #)*""").matches(rows.last()), rows.last())
  }

  @Test
  fun above_outputIsNotATerminal_printsRecordsAndProgressAsWholeLines() {
    // As `ketch -v <url> > debug.log`: a file keeps every carriage return the terminal would
    // act on, which put the progress line and spaces in front of each record.
    val line = ProgressLine(out, interactive = false)
    val logger = PrintingLogger(LogLevel.WARN).above(line)
    line.show("[Downloading] 50%  1.0 MB / 2.0 MB  1.0 MB/s")

    logger.w("[WARN] [Tracker] Tracker warns about 08ada5a7a618")
    logger.e("[ERROR] [Execution] Couldn't save the piece")
    line.printAbove("[Stalled] No peers, still looking")
    line.printBelow("Download completed: /tmp/a.bin")

    assertEquals(
      listOf(
        "[Downloading] 50%  1.0 MB / 2.0 MB  1.0 MB/s",
        "[WARN] [Tracker] Tracker warns about 08ada5a7a618",
        "[ERROR] [Execution] Couldn't save the piece",
        "[Stalled] No peers, still looking",
        "Download completed: /tmp/a.bin",
        "",
      ),
      output().split('\n'),
    )
  }

  @Test
  fun show_outputIsNotATerminal_printsALineAtMostEveryFiveSeconds() {
    // Updates come several times a second: a line for each would flood the file.
    val time = TestTimeSource()
    val line = ProgressLine(out, interactive = false, time)

    line.show("[Downloading] 10%")
    time += 1.seconds
    line.show("[Downloading] 20%")
    time += 4.seconds
    line.show("[Downloading] 30%")
    line.end()

    assertEquals("[Downloading] 10%\n[Downloading] 30%\n", output())
  }

  /** What was written to [terminal], unrendered. */
  private fun output(): String {
    out.flush()
    err.flush()
    return terminal.toString(Charsets.UTF_8)
  }

  /** The rows [terminal] shows, empty last one left out. */
  private fun screen(): List<String> = terminalRows(output()).dropLastWhile { it.isEmpty() }

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

  private companion object {
    const val LOGGING_THREADS = 4
    const val RECORDS = 500
    const val UPDATES = 20_000
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
