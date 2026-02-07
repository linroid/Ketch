package com.linroid.kdown

/**
 * Logger wrapper for KDown components that adds tag prefixes.
 *
 * @suppress This is internal API and should not be used directly by library users.
 */
internal class KDownLogger(private val logger: Logger) {
  fun v(tag: String, message: () -> String) {
    logger.v { "[$tag] ${message()}" }
  }

  fun d(tag: String, message: () -> String) {
    logger.d { "[$tag] ${message()}" }
  }

  fun i(tag: String, message: () -> String) {
    logger.i { "[$tag] ${message()}" }
  }

  fun w(tag: String, throwable: Throwable? = null, message: () -> String) {
    logger.w(message = { "[$tag] ${message()}" }, throwable = throwable)
  }

  fun e(tag: String, throwable: Throwable? = null, message: () -> String) {
    logger.e(message = { "[$tag] ${message()}" }, throwable = throwable)
  }
}
