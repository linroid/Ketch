package com.linroid.ketch.ftp

import com.linroid.ketch.api.KetchError

/**
 * Maps FTP reply codes and exceptions to [KetchError] types.
 *
 * FTP-specific protocol errors use [KetchError.SourceError] with
 * `sourceType = "ftp"` rather than [KetchError.Http], since FTP
 * reply codes have different semantics than HTTP status codes.
 *
 * Only network-level and disk-level errors use their respective
 * [KetchError] subtypes, as those represent the same failure modes
 * regardless of protocol.
 */
internal object FtpError {

  /**
   * Maps an FTP reply code to the appropriate [KetchError].
   *
   * RFC 959 reply code mapping:
   * - 421 (service not available) -> Network
   * - 425, 426 (data connection issues) -> Network
   * - 452, 552 (storage issues) -> Disk
   * - Other 4xx -> Network (transient, retryable)
   * - 430, 530, 450, 451, 500-504, 550, 551, 553 -> SourceError
   * - Other 5xx -> SourceError
   */
  fun fromReply(reply: FtpReply): KetchError {
    return when (reply.code) {
      // Service not available / connection issues
      421 -> KetchError.Network(
        Exception("FTP ${reply.code}: ${reply.message}")
      )
      425, 426 -> KetchError.Network(
        Exception("FTP ${reply.code}: ${reply.message}")
      )

      // Storage / disk errors
      452, 552 -> KetchError.Disk(
        Exception("FTP ${reply.code}: ${reply.message}")
      )

      // Other 4xx: transient / retryable network errors
      in 400..499 -> KetchError.Network(
        Exception("FTP ${reply.code}: ${reply.message}")
      )

      // All 5xx: FTP protocol errors (auth, file not found, etc.)
      in 500..599 -> KetchError.SourceError(
        sourceType = FtpDownloadSource.TYPE,
        cause = Exception("FTP ${reply.code}: ${reply.message}"),
      )

      else -> KetchError.Unknown(
        errorMessage = "Unexpected FTP reply: " +
          "${reply.code} ${reply.message}"
      )
    }
  }

  /** Wraps a network-level exception as [KetchError.Network]. */
  fun fromException(e: Exception): KetchError {
    return KetchError.Network(e)
  }
}
