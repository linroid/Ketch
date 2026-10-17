package com.linroid.ketch.remote

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.Logger

/** Runs [block] and returns the warnings and errors Ketch logged meanwhile. */
internal suspend fun warningsDuring(block: suspend () -> Unit): List<String> {
  val warnings = mutableListOf<String>()
  KetchLogger.setLogger(object : Logger {
    override fun v(message: String) {}
    override fun d(message: String) {}
    override fun i(message: String) {}
    override fun w(message: String, throwable: Throwable?) {
      warnings += message
    }
    override fun e(message: String, throwable: Throwable?) {
      warnings += message
    }
  })
  try {
    block()
  } finally {
    KetchLogger.setLogger(Logger.None)
  }
  return warnings
}
