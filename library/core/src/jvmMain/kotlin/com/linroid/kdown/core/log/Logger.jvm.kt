package com.linroid.kdown.core.log

internal actual fun consoleLogger(minLevel: LogLevel): Logger =
  object : Logger {
    override fun v(message: () -> String) {
      if (minLevel <= LogLevel.VERBOSE) println("[VERBOSE] ${message()}")
    }

    override fun d(message: () -> String) {
      if (minLevel <= LogLevel.DEBUG) println("[DEBUG] ${message()}")
    }

    override fun i(message: () -> String) {
      if (minLevel <= LogLevel.INFO) println("[INFO] ${message()}")
    }

    override fun w(message: () -> String, throwable: Throwable?) {
      if (minLevel <= LogLevel.WARN) {
        println("[WARN] ${message()}")
        throwable?.printStackTrace()
      }
    }

    override fun e(message: () -> String, throwable: Throwable?) {
      if (minLevel <= LogLevel.ERROR) {
        System.err.println("[ERROR] ${message()}")
        throwable?.printStackTrace()
      }
    }
  }
