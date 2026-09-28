package com.linroid.ketch.api.log

// Errors go to stderr; a record and its stack trace always share one stream.
internal actual fun consoleLogger(minLevel: LogLevel): Logger =
  FormattedConsoleLogger(minLevel) { level, line ->
    if (level == LogLevel.ERROR) System.err.println(line) else println(line)
  }
