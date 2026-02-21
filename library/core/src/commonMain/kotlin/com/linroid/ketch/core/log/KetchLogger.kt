package com.linroid.ketch.core.log

/**
 * Tagged logger for Ketch components.
 *
 * Each component creates its own instance with a descriptive [tag].
 * The global [Logger] backend is set once by the [Ketch][com.linroid.ketch.core.Ketch]
 * instance and shared across all [KetchLogger] instances.
 *
 * ```kotlin
 * private val logger = KetchLogger("Coordinator")
 * logger.d { "state changed" }  // emits "[Coordinator] state changed"
 * ```
 *
 * @param tag component name prepended to every log message
 * @suppress This is internal API and should not be used directly by library users.
 */
class KetchLogger(private val tag: String) {

  fun v(message: () -> String) {
    logger.v { "[$tag] ${message()}" }
  }

  fun d(message: () -> String) {
    logger.d { "[$tag] ${message()}" }
  }

  fun i(message: () -> String) {
    logger.i { "[$tag] ${message()}" }
  }

  fun w(throwable: Throwable? = null, message: () -> String) {
    logger.w(message = { "[$tag] ${message()}" }, throwable = throwable)
  }

  fun e(throwable: Throwable? = null, message: () -> String) {
    logger.e(message = { "[$tag] ${message()}" }, throwable = throwable)
  }

  companion object {
    var logger: Logger = Logger.None
      private set

    fun setLogger(logger: Logger) {
      this.logger = logger
    }
  }
}
