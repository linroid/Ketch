package com.linroid.ketch.api.log

/** A log record's severity; loggers drop records below their minimum. */
enum class LogLevel {
  VERBOSE, DEBUG, INFO, WARN, ERROR
}

/**
 * Where Ketch's logs go: [None] (the default), [console], `KermitLogger` from `library:kermit`, or
 * your own implementation. Messages arrive built; [KetchLogger] skips building those
 * [isLoggable] rejects.
 *
 * ```kotlin
 * val ketch = Ketch(httpEngine = KtorHttpEngine(), logger = Logger.console(LogLevel.INFO))
 * ```
 */
interface Logger {
  /** Detailed diagnostics, such as per-peer traffic. */
  fun v(message: String)

  /** Internal operations. */
  fun d(message: String)

  /** User-visible events and state changes. */
  fun i(message: String)

  /** Retries and recoverable problems. */
  fun w(message: String, throwable: Throwable? = null)

  /** Failures. */
  fun e(message: String, throwable: Throwable? = null)

  /** Whether records at [level] are emitted; override it to skip building filtered messages. */
  fun isLoggable(level: LogLevel): Boolean = true

  companion object {
    /** Discards everything; the default. */
    val None: Logger = object : Logger {
      override fun v(message: String) {}
      override fun d(message: String) {}
      override fun i(message: String) {}
      override fun w(message: String, throwable: Throwable?) {}
      override fun e(message: String, throwable: Throwable?) {}
      override fun isLoggable(level: LogLevel): Boolean = false
    }

    /** The platform's console (Logcat on Android), from [minLevel]. */
    fun console(minLevel: LogLevel = LogLevel.VERBOSE): Logger =
      consoleLogger(minLevel)

    /**
     * Sends every record to each of [loggers], which filter on their own. [None] entries are
     * skipped, and nothing else left returns [None], keeping logging free.
     */
    fun combine(vararg loggers: Logger): Logger {
      val active = loggers.filter { it !== None }
      return when (active.size) {
        0 -> None
        1 -> active.single()
        else -> CombinedLogger(active)
      }
    }
  }
}

private class CombinedLogger(private val loggers: List<Logger>) : Logger {
  override fun v(message: String) = loggers.forEach { it.v(message) }

  override fun d(message: String) = loggers.forEach { it.d(message) }

  override fun i(message: String) = loggers.forEach { it.i(message) }

  override fun w(message: String, throwable: Throwable?) =
    loggers.forEach { it.w(message, throwable) }

  override fun e(message: String, throwable: Throwable?) =
    loggers.forEach { it.e(message, throwable) }

  override fun isLoggable(level: LogLevel): Boolean = loggers.any { it.isLoggable(level) }
}

/** The platform's console logger. */
internal expect fun consoleLogger(minLevel: LogLevel): Logger
