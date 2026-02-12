package com.linroid.kdown.log

import co.touchlab.kermit.Logger as KermitLib
import co.touchlab.kermit.Severity
import co.touchlab.kermit.StaticConfig
import co.touchlab.kermit.platformLogWriter
import com.linroid.kdown.core.log.Logger

/**
 * Kermit-based logger implementation for KDown.
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
 * val kdown = KDown(
 *   httpEngine = KtorHttpEngine(),
 *   logger = logger
 * )
 * ```
 *
 * @param minSeverity Minimum severity level for logs. Default is Info.
 * @param tag Optional tag prefix for all log messages. Default is "KDown".
 * @param config Optional custom Kermit configuration. If not provided, uses platform default writer.
 */
class KermitLogger(
  minSeverity: Severity = Severity.Info,
  private val tag: String = "KDown",
  config: StaticConfig? = null,
) : Logger {

  private val kermit = KermitLib(
    config = config ?: StaticConfig(
      minSeverity = minSeverity,
      logWriterList = listOf(platformLogWriter()),
    ),
    tag = tag,
  )

  override fun v(message: () -> String) {
    kermit.v(messageString = message())
  }

  override fun d(message: () -> String) {
    kermit.d(messageString = message())
  }

  override fun i(message: () -> String) {
    kermit.i(messageString = message())
  }

  override fun w(message: () -> String, throwable: Throwable?) {
    kermit.w(messageString = message(), throwable = throwable)
  }

  override fun e(message: () -> String, throwable: Throwable?) {
    kermit.e(messageString = message(), throwable = throwable)
  }
}
