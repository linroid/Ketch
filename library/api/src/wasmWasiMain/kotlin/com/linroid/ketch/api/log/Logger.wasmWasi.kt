package com.linroid.ketch.api.log

internal actual fun consoleLogger(minLevel: LogLevel): Logger =
  FormattedConsoleLogger(minLevel) { _, line -> println(line) }
