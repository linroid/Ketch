package com.linroid.kdown.api

sealed class KDownError(
  override val message: String?,
  override val cause: Throwable? = null
) : Exception(message, cause) {

  data class Network(
    override val cause: Throwable? = null
  ) : KDownError("Network error occurred", cause)

  data class Http(
    val code: Int,
    val statusMessage: String? = null
  ) : KDownError("HTTP error $code: $statusMessage")

  data class Disk(
    override val cause: Throwable? = null
  ) : KDownError("Disk I/O error", cause)

  data object Unsupported : KDownError("Operation not supported by server")

  data class ValidationFailed(
    val reason: String
  ) : KDownError("Validation failed: $reason")

  data object Canceled : KDownError("Download was canceled")

  data class SourceError(
    val sourceType: String,
    override val cause: Throwable? = null
  ) : KDownError("Source '$sourceType' error", cause)

  data class Unknown(
    override val cause: Throwable? = null
  ) : KDownError("Unknown error occurred", cause)

  val isRetryable: Boolean
    get() = when (this) {
      is Network -> true
      is Http -> code in 500..599
      is Disk -> false
      is Unsupported -> false
      is ValidationFailed -> false
      is Canceled -> false
      is SourceError -> false
      is Unknown -> false
    }
}
