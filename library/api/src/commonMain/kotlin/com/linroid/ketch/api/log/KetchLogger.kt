package com.linroid.ketch.api.log

import com.linroid.ketch.api.InternalKetchApi

/**
 * Tagged logger for Ketch components.
 *
 * Each component creates its own instance with a descriptive [tag].
 * The global [Logger] backend is set once by the [Ketch][com.linroid.ketch.core.Ketch]
 * instance and shared across all [KetchLogger] instances.
 *
 * All logging methods are `inline` and build the message only when [Logger.isLoggable], so
 * disabled or filtered-out logging allocates nothing.
 *
 * ```kotlin
 * private val logger = KetchLogger("Coordinator")
 * logger.d { "state changed" }  // emits "[Coordinator] state changed"
 * ```
 *
 * @param tag component name prepended to every log message
 * @suppress This is internal API and should not be used directly by library users.
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
