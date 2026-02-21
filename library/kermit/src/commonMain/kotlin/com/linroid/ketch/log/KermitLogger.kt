package com.linroid.ketch.log

import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import co.touchlab.kermit.platformLogWriter
import com.linroid.ketch.core.log.Logger
import co.touchlab.kermit.Logger as KermitLib

/**
 * Kermit-based logger implementation for Ketch.
 *
 * Provides structured logging across all platforms using the Kermit library.
 *
 * Example usage:
 * ```kotlin
 * val logger = KermitLogger(
 *   minSeverity = Severity.Debug,
 *   tag = "MyApp"
 * )
 *
 * val ketch = Ketch(
 *   httpEngine = KtorHttpEngine(),
 *   logger = logger
 * )
 * ```
 *
 * @param minSeverity Minimum severity level for logs. Default is Info.
 * @param tag Optional tag prefix for all log messages. Default is "Ketch".
 * @param config Optional custom Kermit configuration. If not provided, uses platform default writer.
 */
class KermitLogger(
  minSeverity: Severity = Severity.Info,
  private val tag: String = "Ketch",
  config: StaticConfig? = null,
) : Logger {

  private val kermit = KermitLib(
    config = config ?: StaticConfig(
      minSeverity = minSeverity,
      logWriterList = listOf(platformLogWriter()),
    ),
    tag = tag,
  )

  override fun v(message: String) {
    kermit.v(messageString = message)
  }

  override fun d(message: String) {
    kermit.d(messageString = message)
  }

  override fun i(message: String) {
    kermit.i(messageString = message)
  }

  override fun w(message: String, throwable: Throwable?) {
    kermit.w(messageString = message, throwable = throwable)
  }

  override fun e(message: String, throwable: Throwable?) {
    kermit.e(messageString = message, throwable = throwable)
  }
}
