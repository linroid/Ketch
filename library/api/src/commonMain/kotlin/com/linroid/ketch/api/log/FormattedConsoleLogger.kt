package com.linroid.ketch.api.log

/** A console logger writing each [formatted][formatLogLine] record in one [write]. */
internal class FormattedConsoleLogger(
  private val minLevel: LogLevel,
  private val write: (level: LogLevel, line: String) -> Unit,
) : Logger {
  override fun v(message: String) = log(LogLevel.VERBOSE, message, null)

  override fun d(message: String) = log(LogLevel.DEBUG, message, null)

  override fun i(message: String) = log(LogLevel.INFO, message, null)

  override fun w(message: String, throwable: Throwable?) = log(LogLevel.WARN, message, throwable)

  override fun e(message: String, throwable: Throwable?) = log(LogLevel.ERROR, message, throwable)

  override fun isLoggable(level: LogLevel): Boolean = level >= minLevel

  private fun log(level: LogLevel, message: String, throwable: Throwable?) {
    if (isLoggable(level)) write(level, formatLogLine(level, message, throwable))
  }
}
