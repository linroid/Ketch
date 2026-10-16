package com.linroid.ketch.api.log

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

class LoggerTest {
  private class RecordingLogger(private val minLevel: LogLevel = LogLevel.VERBOSE) : Logger {
    val records = mutableListOf<String>()

    override fun isLoggable(level: LogLevel): Boolean = level >= minLevel

    override fun v(message: String) {
      records += "V $message"
    }

    override fun d(message: String) {
      records += "D $message"
    }

    override fun i(message: String) {
      records += "I $message"
    }

    override fun w(message: String, throwable: Throwable?) {
      records += "W $message ${throwable?.message}"
    }

    override fun e(message: String, throwable: Throwable?) {
      records += "E $message ${throwable?.message}"
    }
  }

  @Test
  fun combine_severalLoggers_forwardsEveryRecordToEach() {
    val console = RecordingLogger()
    val file = RecordingLogger()
    val logger = Logger.combine(console, Logger.None, file)

    logger.v("verbose")
    logger.d("debug")
    logger.i("info")
    logger.w("warn", IllegalStateException("cause"))
    logger.e("error")

    val expected = listOf("V verbose", "D debug", "I info", "W warn cause", "E error null")
    assertEquals(expected, console.records)
    assertEquals(expected, file.records)
  }

  @Test
  fun combine_onlyNone_returnsNoneToKeepLoggingFree() {
    assertSame(Logger.None, Logger.combine())
    assertSame(Logger.None, Logger.combine(Logger.None, Logger.None))
  }

  @Test
  fun combine_oneActiveLogger_returnsItUnwrapped() {
    val file = RecordingLogger()
    assertSame(file, Logger.combine(Logger.None, file))
  }

  @Test
  fun combine_isLoggable_whenAnyLoggerIs() {
    val logger = Logger.combine(RecordingLogger(LogLevel.WARN), RecordingLogger(LogLevel.INFO))
    assertFalse(logger.isLoggable(LogLevel.DEBUG))
    assertTrue(logger.isLoggable(LogLevel.INFO))
  }

  @Test
  fun none_isNeverLoggable() {
    assertFalse(Logger.None.isLoggable(LogLevel.ERROR))
  }

  @Test
  fun console_isLoggable_fromItsMinimumLevel() {
    val logger = Logger.console(LogLevel.INFO)
    assertFalse(logger.isLoggable(LogLevel.DEBUG))
    assertTrue(logger.isLoggable(LogLevel.INFO))
  }

  @Test
  fun ketchLogger_belowTheLevel_neverBuildsTheMessage() {
    val recording = RecordingLogger(LogLevel.INFO)
    KetchLogger.setLogger(recording)
    try {
      val log = KetchLogger("Test")
      var built = 0
      log.v { built++; "verbose" }
      log.d { built++; "debug" }
      log.i { built++; "info" }
      assertEquals(1, built)
      assertEquals(listOf("I [Test] info"), recording.records)
    } finally {
      KetchLogger.setLogger(Logger.None)
    }
  }
}
