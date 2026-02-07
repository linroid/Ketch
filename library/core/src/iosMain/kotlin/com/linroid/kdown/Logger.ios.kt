package com.linroid.kdown

import platform.Foundation.NSLog

internal actual fun consoleLogger(): Logger = object : Logger {
  override fun v(message: () -> String) {
    NSLog("[VERBOSE] ${message()}")
  }

  override fun d(message: () -> String) {
    NSLog("[DEBUG] ${message()}")
  }

  override fun i(message: () -> String) {
    NSLog("[INFO] ${message()}")
  }

  override fun w(message: () -> String, throwable: Throwable?) {
    NSLog("[WARN] ${message()}")
    throwable?.let {
      NSLog("  Exception: ${it.message}")
      NSLog("  ${it.stackTraceToString()}")
    }
  }

  override fun e(message: () -> String, throwable: Throwable?) {
    NSLog("[ERROR] ${message()}")
    throwable?.let {
      NSLog("  Exception: ${it.message}")
      NSLog("  ${it.stackTraceToString()}")
    }
  }
}
