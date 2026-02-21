package com.linroid.ketch.core.log

/**
 * Tagged logger for Ketch components.
 *
 * Each component creates its own instance with a descriptive [tag].
 * The global [Logger] backend is set once by the [Ketch][com.linroid.ketch.core.Ketch]
 * instance and shared across all [KetchLogger] instances.
 *
 * All logging methods are `inline` with a fast-path check for [Logger.None],
 * so when logging is disabled (the default) there is zero allocation overhead —
 * the message lambda is never created and the string is never constructed.
 *
 * ```kotlin
 * private val logger = KetchLogger("Coordinator")
 * logger.d { "state changed" }  // emits "[Coordinator] state changed"
 * ```
 *
 * @param tag component name prepended to every log message
 * @suppress This is internal API and should not be used directly by library users.
 */
class KetchLogger(@PublishedApi internal val tag: String) {

  inline fun v(message: () -> String) {
    val l = logger
    if (l !== Logger.None) l.v("[$tag] ${message()}")
  }

  inline fun d(message: () -> String) {
    val l = logger
    if (l !== Logger.None) l.d("[$tag] ${message()}")
  }

  inline fun i(message: () -> String) {
    val l = logger
    if (l !== Logger.None) l.i("[$tag] ${message()}")
  }

  inline fun w(throwable: Throwable? = null, message: () -> String) {
    val l = logger
    if (l !== Logger.None) l.w("[$tag] ${message()}", throwable)
  }

  inline fun e(throwable: Throwable? = null, message: () -> String) {
    val l = logger
    if (l !== Logger.None) l.e("[$tag] ${message()}", throwable)
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
