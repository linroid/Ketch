package com.linroid.ketch.api.log

/**
 * Log level for filtering log output.
 *
 * Messages at or above the configured level are emitted;
 * messages below are silently discarded.
 */
enum class LogLevel {
  VERBOSE, DEBUG, INFO, WARN, ERROR
}

/**
 * Logger abstraction for Ketch.
 *
 * Ketch supports pluggable logging with zero overhead when disabled (default).
 * All log methods accept a pre-built [String] message. Lazy evaluation is
 * handled by [KetchLogger]'s `inline` functions, which skip the message
 * construction entirely when [Logger.None] is active.
 *
 * ## Usage
 *
 * ### Default (No Logging)
 * ```kotlin
 * val ketch = Ketch(httpEngine = KtorHttpEngine())  // Logger.None is default
 * ```
 *
 * ### Console Logging (Built-in)
 * ```kotlin
 * val ketch = Ketch(
 *   httpEngine = KtorHttpEngine(),
 *   logger = Logger.console()  // Platform-specific console output
 * )
 * ```
 *
 * ### Console Logging with Level Filter
 * ```kotlin
 * val ketch = Ketch(
 *   httpEngine = KtorHttpEngine(),
 *   logger = Logger.console(LogLevel.INFO)  // Only INFO and above
 * )
 * ```
 *
 * ### Structured Logging (Kermit Module)
 * ```kotlin
 * val ketch = Ketch(
 *   httpEngine = KtorHttpEngine(),
 *   logger = KermitLogger(minSeverity = Severity.Debug)
 * )
 * ```
 *
 * ### Custom Logger
 * ```kotlin
 * class MyLogger : Logger {
 *   override fun v(message: String) { println(message) }
 *   override fun d(message: String) { println(message) }
 *   override fun i(message: String) { println(message) }
 *   override fun w(message: String, throwable: Throwable?) { println(message) }
 *   override fun e(message: String, throwable: Throwable?) { System.err.println(message) }
 * }
 * ```
 *
 * ## Log Levels
 *
 * - **Verbose**: Detailed diagnostics (segment-level progress)
 * - **Debug**: Internal operations (server detection, metadata operations)
 * - **Info**: User-facing events (download start/complete, server capabilities)
 * - **Warn**: Recoverable errors (retry attempts, validation warnings)
 * - **Error**: Fatal failures (download failures, network errors)
 *
 * @see Logger.console
 * @see Logger.None
 */
interface Logger {
  /** Log a verbose message. Used for detailed diagnostic information. */
  fun v(message: String)

  /** Log a debug message. Used for debugging information. */
  fun d(message: String)

  /** Log an info message. Used for general informational messages. */
  fun i(message: String)

  /**
   * Log a warning message. Used for potentially harmful situations.
   *
   * @param message Log message
   * @param throwable Optional throwable to log
   */
  fun w(message: String, throwable: Throwable? = null)

  /**
   * Log an error message. Used for error events.
   *
   * @param message Log message
   * @param throwable Optional throwable to log
   */
  fun e(message: String, throwable: Throwable? = null)

  companion object {
    /**
     * No-op logger that discards all log messages.
     * This is the default logger if none is provided.
     */
    val None: Logger = object : Logger {
      override fun v(message: String) {}
      override fun d(message: String) {}
      override fun i(message: String) {}
      override fun w(message: String, throwable: Throwable?) {}
      override fun e(message: String, throwable: Throwable?) {}
    }

    /**
     * Simple console logger that prints messages to stdout/stderr.
     * Useful for quick debugging or CLI applications.
     * Platform-specific implementation.
     *
     * @param minLevel minimum log level to emit (default: [LogLevel.VERBOSE])
     */
    fun console(minLevel: LogLevel = LogLevel.VERBOSE): Logger =
      consoleLogger(minLevel)
  }
}

/**
 * Platform-specific console logger implementation.
 */
internal expect fun consoleLogger(minLevel: LogLevel): Logger
