package com.linroid.kdown.core.log

internal actual fun consoleLogger(): Logger = object : Logger {
  override fun v(message: () -> String) {
    println("[VERBOSE] ${message()}")
  }

  override fun d(message: () -> String) {
    println("[DEBUG] ${message()}")
  }

  override fun i(message: () -> String) {
    println("[INFO] ${message()}")
  }

  override fun w(message: () -> String, throwable: Throwable?) {
    println("[WARN] ${message()}")
    throwable?.printStackTrace()
  }

  override fun e(message: () -> String, throwable: Throwable?) {
    System.err.println("[ERROR] ${message()}")
    throwable?.printStackTrace()
  }
}
