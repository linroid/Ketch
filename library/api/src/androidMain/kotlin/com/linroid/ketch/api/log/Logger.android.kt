package com.linroid.ketch.api.log

import android.util.Log
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger

internal actual fun consoleLogger(minLevel: LogLevel): Logger =
  object : Logger {
    override fun v(message: String) {
      if (minLevel <= LogLevel.VERBOSE) Log.v("Ketch", message)
    }

    override fun d(message: String) {
      if (minLevel <= LogLevel.DEBUG) Log.d("Ketch", message)
    }

    override fun i(message: String) {
      if (minLevel <= LogLevel.INFO) Log.i("Ketch", message)
    }

    override fun w(message: String, throwable: Throwable?) {
      if (minLevel <= LogLevel.WARN) Log.w("Ketch", message, throwable?.redacted())
    }

    override fun e(message: String, throwable: Throwable?) {
      if (minLevel <= LogLevel.ERROR) Log.e("Ketch", message, throwable?.redacted())
    }
  }

/**
 * Copy of this error chain whose messages pass through [redactUrlsIn], keeping each stack
 * trace, so Logcat prints the usual trace (and splits it) without URL credentials.
 */
private fun Throwable.redacted(depth: Int = 1): Throwable {
  val next = cause?.takeIf { it !== this && depth < MAX_LOGGED_CAUSES }?.redacted(depth + 1)
  return RedactedThrowable(this, next)
}

private class RedactedThrowable(original: Throwable, cause: Throwable?) :
  Throwable(original.message?.let(::redactUrlsIn), cause) {
  private val name = original.javaClass.name

  init {
    stackTrace = original.stackTrace
  }

  override fun toString(): String = message?.let { "$name: $it" } ?: name
}
