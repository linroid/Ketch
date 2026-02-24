package com.linroid.ketch.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient

/**
 * Sealed hierarchy of all errors that Ketch can produce.
 *
 * Use [isRetryable] to determine whether the operation should be
 * retried automatically. Only transient failures ([Network],
 * server-side [Http] 5xx, and rate-limiting 429) are considered
 * retryable.
 *
 * @property message human-readable error description
 * @property cause underlying exception, if any
 */
@Serializable
sealed class KetchError(
  override val message: String?,
  @Transient override val cause: Throwable? = null,
) : Exception(message, cause) {

  /** Connection or timeout failure. Always retryable. */
  @Serializable
  @SerialName("network")
  data class Network(
    @Transient override val cause: Throwable? = null,
  ) : KetchError("Network error occurred", cause)

  /**
   * Non-success HTTP status code.
   * Retryable for server errors (5xx) and rate limiting (429).
   *
   * @property code the HTTP status code
   * @property statusMessage optional reason phrase from the server
   * @property retryAfterSeconds value of the `Retry-After` header in
   *   seconds, if the server provided one (typically on 429 responses).
   *   Falls back to `RateLimit-Reset` when `Retry-After` is absent.
   * @property rateLimitRemaining value of the `RateLimit-Remaining`
   *   header, indicating how many requests remain in the current
   *   rate limit window. Used on 429 to inform connection reduction.
   */
  @Serializable
  @SerialName("http")
  data class Http(
    val code: Int,
    val statusMessage: String? = null,
    val retryAfterSeconds: Long? = null,
    val rateLimitRemaining: Long? = null,
    @Transient override val cause: Throwable? = null,
  ) : KetchError("HTTP error $code: $statusMessage", cause)

  /** File I/O failure (write, flush, preallocate). Not retryable. */
  @Serializable
  @SerialName("disk")
  data class Disk(
    @Transient override val cause: Throwable? = null,
  ) : KetchError("Disk I/O error", cause)

  /** Server does not support a required feature (e.g., byte ranges). */
  @Serializable
  @SerialName("unsupported")
  data class Unsupported(
    @Transient override val cause: Throwable? = null,
  ) : KetchError("Operation not supported by server", cause)

  /**
   * Server file has changed since the download started (ETag,
   * Last-Modified, or MDTM mismatch). Not retryable — the download
   * must be restarted from scratch.
   *
   * @property reason description of what changed
   */
  @Serializable
  @SerialName("file_changed")
  data class FileChanged(
    val reason: String,
    @Transient override val cause: Throwable? = null,
  ) : KetchError("File changed on server: $reason", cause)

  /**
   * Resume state (stored JSON) could not be parsed. Not retryable —
   * the download must be restarted from scratch.
   *
   * @property detail optional detail about what was corrupt
   */
  @Serializable
  @SerialName("corrupt_resume_state")
  data class CorruptResumeState(
    val detail: String? = null,
    @Transient override val cause: Throwable? = null,
  ) : KetchError(
    "Corrupt resume state" + if (detail != null) ": $detail" else "",
    cause,
  )

  /** Download was explicitly canceled by the user. */
  @Serializable
  @SerialName("canceled")
  data class Canceled(
    @Transient override val cause: Throwable? = null,
  ) : KetchError("Download was canceled", cause)

  /**
   * Error originating from a pluggable source.
   *
   * @property sourceType identifier of the source that failed
   */
  @Serializable
  @SerialName("source")
  data class SourceError(
    val sourceType: String,
    @Transient override val cause: Throwable? = null,
  ) : KetchError("Source '$sourceType' error", cause)

  /**
   * Authentication failed (e.g., FTP 530 "Not logged in").
   *
   * @property sourceType identifier of the source that rejected credentials
   */
  @Serializable
  @SerialName("auth_failed")
  data class AuthenticationFailed(
    val sourceType: String,
    @Transient override val cause: Throwable? = null,
  ) : KetchError(
    "Authentication failed for '$sourceType'", cause,
  )

  /** Catch-all for unexpected errors. Not retryable. */
  @Serializable
  @SerialName("unknown")
  data class Unknown(
    @Transient override val cause: Throwable? = null,
    val errorMessage: String? = null,
  ) : KetchError(errorMessage ?: "Unknown error occurred", cause)

  /**
   * Whether this error is transient and the download should be retried.
   * [Network], [Http] with a 5xx status code, and [Http] 429
   * (Too Many Requests) are retryable.
   */
  val isRetryable: Boolean
    get() = when (this) {
      is Network -> true
      is Http -> code in 500..599 || code == 429
      is Disk -> false
      is Unsupported -> false
      is FileChanged -> false
      is CorruptResumeState -> false
      is Canceled -> false
      is SourceError -> false
      is AuthenticationFailed -> false
      is Unknown -> false
    }
}
