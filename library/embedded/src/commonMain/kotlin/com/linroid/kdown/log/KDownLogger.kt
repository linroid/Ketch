package com.linroid.kdown.log

/**
 * Logger holder for KDown components.
 * This is set by the KDown instance and used by all KDown modules.
 *
 * @suppress This is internal API and should not be used directly by library users.
 */
object KDownLogger {
  var logger: Logger = Logger.None
    private set

  fun setLogger(logger: Logger) {
    this.logger = logger
  }

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
