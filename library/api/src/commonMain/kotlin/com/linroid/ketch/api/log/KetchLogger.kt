package com.linroid.ketch.api.log

import com.linroid.ketch.api.InternalKetchApi

/**
 * A component's logger, prefixing messages with `[tag]`. All instances share the [Logger] that
 * `Ketch` sets. Messages are built only when [Logger.isLoggable], so filtered logging allocates
 * nothing.
 *
 * ```kotlin
 * private val log = KetchLogger("Coordinator")
 * log.d { "state changed" }  // [Coordinator] state changed
 * ```
 *
 * @suppress
 */
@InternalKetchApi
class KetchLogger(@PublishedApi internal val tag: String) {

  inline fun v(message: () -> String) {
    val l = logger
    if (l !== Logger.None && l.isLoggable(LogLevel.VERBOSE)) l.v("[$tag] ${message()}")
  }

  inline fun d(message: () -> String) {
    val l = logger
    if (l !== Logger.None && l.isLoggable(LogLevel.DEBUG)) l.d("[$tag] ${message()}")
  }

  inline fun i(message: () -> String) {
    val l = logger
    if (l !== Logger.None && l.isLoggable(LogLevel.INFO)) l.i("[$tag] ${message()}")
  }

  inline fun w(throwable: Throwable? = null, message: () -> String) {
    val l = logger
    if (l !== Logger.None && l.isLoggable(LogLevel.WARN)) {
      l.w("[$tag] ${message()}", throwable)
    }
  }

  inline fun e(throwable: Throwable? = null, message: () -> String) {
    val l = logger
    if (l !== Logger.None && l.isLoggable(LogLevel.ERROR)) {
      l.e("[$tag] ${message()}", throwable)
    }
  }

  companion object {
    @PublishedApi
    internal var logger: Logger = Logger.None
      private set

    fun setLogger(logger: Logger) {
      this.logger = logger
    }
  }
}
