package com.linroid.ketch.ftp

/**
 * An FTP server reply consisting of a 3-digit code and text message.
 *
 * @property code the 3-digit FTP reply code (RFC 959)
 * @property message the human-readable reply text
 */
internal data class FtpReply(
  val code: Int,
  val message: String,
) {
  /** True if this is a positive completion reply (2xx). */
  val isPositiveCompletion: Boolean get() = code in 200..299

  /** True if this is a positive preliminary reply (1xx). */
  val isPositivePreliminary: Boolean get() = code in 100..199

  /** True if this is an error reply (4xx or 5xx). */
  val isError: Boolean get() = code in 400..599

  override fun toString(): String = "$code $message"
}