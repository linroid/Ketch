package com.linroid.ketch.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Every error Ketch reports; [isRetryable] tells transient ones apart.
 *
 * Serialized with a `type`, such as `{"type":"network","message":"..."}`. Read errors from
 * other versions with [tolerantSerializer].
 *
 * @property message human-readable error description
 * @property cause underlying exception, if any
 */
@Serializable
sealed class KetchError(
  override val message: String?,
  @Transient override val cause: Throwable? = null,
) : Exception(message, cause) {

  /** Connection or timeout failure. Retryable. */
  @Serializable
  @SerialName("network")
  data class Network(
    @Transient override val cause: Throwable? = null,
  ) : KetchError("Network error occurred", cause)

  /**
   * An HTTP error status; retryable for 5xx and 429.
   *
   * @property statusMessage the server's reason phrase
   * @property retryAfterSeconds `Retry-After`, else `RateLimit-Reset`, in seconds
   * @property rateLimitRemaining `RateLimit-Remaining`, which a 429 uses to cut connections
   */
  @Serializable
  @SerialName("http")
  data class Http(
    val code: Int,
    val statusMessage: String? = null,
    val retryAfterSeconds: Long? = null,
    val rateLimitRemaining: Long? = null,
    @Transient override val cause: Throwable? = null,
  ) : KetchError("HTTP error $code" + statusMessage?.let { ": $it" }.orEmpty(), cause)

  /** File I/O failure. */
  @Serializable
  @SerialName("disk")
  data class Disk(
    @Transient override val cause: Throwable? = null,
  ) : KetchError("Disk I/O error", cause)

  /** Something required is unsupported, such as byte ranges or the content's format. */
  @Serializable
  @SerialName("unsupported")
  data class Unsupported(
    @Transient override val cause: Throwable? = null,
  ) : KetchError("Operation not supported by server", cause)

  /** The file changed on the server since the download started (ETag, date or MDTM). */
  @Serializable
  @SerialName("file_changed")
  data class FileChanged(
    val reason: String,
    @Transient override val cause: Throwable? = null,
  ) : KetchError("File changed on server: $reason", cause)

  /** The saved resume state could not be read; the download must start over. */
  @Serializable
  @SerialName("corrupt_resume_state")
  data class CorruptResumeState(
    val detail: String? = null,
    @Transient override val cause: Throwable? = null,
  ) : KetchError(
    "Corrupt resume state" + if (detail != null) ": $detail" else "",
    cause,
  )

  /** The download was canceled. */
  @Serializable
  @SerialName("canceled")
  data class Canceled(
    @Transient override val cause: Throwable? = null,
  ) : KetchError("Download was canceled", cause)

  /**
   * A download source failed.
   *
   * @property detail an explanation safe to show and save, without credentials
   */
  @Serializable
  @SerialName("source")
  data class SourceError(
    val sourceType: String,
    @Transient override val cause: Throwable? = null,
    val detail: String? = null,
  ) : KetchError(detail ?: "Source '$sourceType' error", cause)

  /** The source rejected the credentials, such as FTP's 530. */
  @Serializable
  @SerialName("auth_failed")
  data class AuthenticationFailed(
    val sourceType: String,
    @Transient override val cause: Throwable? = null,
  ) : KetchError(
    "Authentication failed for '$sourceType'", cause,
  )

  /**
   * The finished file's digest differs from [DownloadRequest.checksum], or from the one the
   * server published. Its progress is discarded, so a resume downloads it again.
   *
   * @property expected the digest it should have, as lowercase hex
   * @property actual the digest it has, as lowercase hex
   * @property published whether [expected] came from the server
   */
  @Serializable
  @SerialName("checksum_mismatch")
  data class ChecksumMismatch(
    val algorithm: ChecksumAlgorithm,
    val expected: String,
    val actual: String,
    val published: Boolean = false,
    @Transient override val cause: Throwable? = null,
  ) : KetchError(
    "${algorithm.label} checksum mismatch: expected $expected, got $actual" +
      if (published) " (digest published by the server)" else "",
    cause,
  )

  /** Any other error. */
  @Serializable
  @SerialName("unknown")
  data class Unknown(
    @Transient override val cause: Throwable? = null,
    val errorMessage: String? = null,
  ) : KetchError(errorMessage ?: "Unknown error occurred", cause)

  /** Whether this error is transient: [Network], and [Http] 5xx or 429. */
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
      is ChecksumMismatch -> false
      is Unknown -> false
    }

  companion object {
    /** Like `serializer()`, but reads an unknown or malformed JSON error as [Unknown]. */
    fun tolerantSerializer(): KSerializer<KetchError> = KetchErrorSerializer
  }
}

// The generated serializer plus an Unknown fallback. Not KetchError's own serializer: the plugin
// only lets subclasses inherit fields from a parent whose serializer it generates.
internal object KetchErrorSerializer : KSerializer<KetchError> {
  private val generated: KSerializer<KetchError> get() = KetchError.serializer()

  override val descriptor: SerialDescriptor get() = generated.descriptor

  override fun serialize(encoder: Encoder, value: KetchError) {
    encoder.encodeSerializableValue(generated, value)
  }

  override fun deserialize(decoder: Decoder): KetchError {
    if (decoder !is JsonDecoder) return decoder.decodeSerializableValue(generated)
    val element = decoder.decodeJsonElement()
    return try {
      decoder.json.decodeFromJsonElement(generated, element)
    } catch (_: IllegalArgumentException) {
      // SerializationException is an IllegalArgumentException.
      KetchError.Unknown(errorMessage = (element as? JsonObject)?.string("message"))
    }
  }

  private fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content
}
