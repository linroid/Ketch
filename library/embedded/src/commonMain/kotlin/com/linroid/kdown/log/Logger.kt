package com.linroid.kdown.log

/**
 * Logger abstraction for KDown.
 *
 * KDown supports pluggable logging with zero overhead when disabled (default).
 * All log methods accept lambda parameters for lazy evaluation.
 *
 * ## Usage
 *
 * ### Default (No Logging)
 * ```kotlin
 * val kdown = KDown(httpEngine = KtorHttpEngine())  // Logger.None is default
 * ```
 *
 * ### Console Logging (Built-in)
 * ```kotlin
 * val kdown = KDown(
 *   httpEngine = KtorHttpEngine(),
 *   logger = Logger.console()  // Platform-specific console output
 * )
 * ```
 *
 * ### Structured Logging (Kermit Module)
 * ```kotlin
 * val kdown = KDown(
 *   httpEngine = KtorHttpEngine(),
 *   logger = KermitLogger(minSeverity = Severity.Debug)
 * )
 * ```
 *
 * ### Custom Logger
 * ```kotlin
 * class MyLogger : Logger {
 *   override fun v(message: () -> String) { println(message()) }
 *   override fun d(message: () -> String) { println(message()) }
 *   override fun i(message: () -> String) { println(message()) }
 *   override fun w(message: () -> String, throwable: Throwable?) { println(message()) }
 *   override fun e(message: () -> String, throwable: Throwable?) { System.err.println(message()) }
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
  /**
   * Log a verbose message. Used for detailed diagnostic information.
   *
   * @param message Lazy message provider (only evaluated if logging is enabled)
   */
  fun v(message: () -> String)

  /**
   * Log a debug message. Used for debugging information.
   *
   * @param message Lazy message provider (only evaluated if logging is enabled)
   */
  fun d(message: () -> String)

  /**
   * Log an info message. Used for general informational messages.
   *
   * @param message Lazy message provider (only evaluated if logging is enabled)
   */
  fun i(message: () -> String)

  /**
   * Log a warning message. Used for potentially harmful situations.
   *
   * @param message Lazy message provider (only evaluated if logging is enabled)
   * @param throwable Optional throwable to log
   */
  fun w(message: () -> String, throwable: Throwable? = null)

  /**
   * Log an error message. Used for error events.
   *
   * @param message Lazy message provider (only evaluated if logging is enabled)
   * @param throwable Optional throwable to log
   */
  fun e(message: () -> String, throwable: Throwable? = null)

  companion object {
    /**
     * No-op logger that discards all log messages.
     * This is the default logger if none is provided.
     */
    val None: Logger = object : Logger {
      override fun v(message: () -> String) {}
      override fun d(message: () -> String) {}
      override fun i(message: () -> String) {}
      override fun w(message: () -> String, throwable: Throwable?) {}
      override fun e(message: () -> String, throwable: Throwable?) {}
    }

    /**
     * Simple console logger that prints all messages to stdout/stderr.
     * Useful for quick debugging or CLI applications.
     * Platform-specific implementation.
     */
    fun console(): Logger = consoleLogger()
  }
}

/**
 * Platform-specific console logger implementation.
 */
internal expect fun consoleLogger(): Logger
