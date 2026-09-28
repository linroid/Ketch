package com.linroid.ketch.api.log

/**
 * Console logger for platforms without a native log facility. Each record, including its
 * stack trace, is formatted by [formatLogLine] and handed to [write] in one call.
 */
internal class FormattedConsoleLogger(
  private val minLevel: LogLevel,
  private val write: (level: LogLevel, line: String) -> Unit,
) : Logger {
  override fun v(message: String) = log(LogLevel.VERBOSE, message, null)

  override fun d(message: String) = log(LogLevel.DEBUG, message, null)

  override fun i(message: String) = log(LogLevel.INFO, message, null)

  override fun w(message: String, throwable: Throwable?) = log(LogLevel.WARN, message, throwable)

  override fun e(message: String, throwable: Throwable?) = log(LogLevel.ERROR, message, throwable)

  private fun log(level: LogLevel, message: String, throwable: Throwable?) {
    if (level >= minLevel) write(level, formatLogLine(level, message, throwable))
  }
}
