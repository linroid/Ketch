package com.linroid.ketch.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.io.encoding.Base64

/**
 * A hash function a [Checksum] names.
 *
 * @property id the name in `<id>:<hex>`, such as `sha256`
 * @property label the display name, such as `SHA-256`
 * @property byteCount the digest size in bytes
 */
@Serializable
enum class ChecksumAlgorithm(val id: String, val label: String, val byteCount: Int) {
  @SerialName("md5")
  MD5("md5", "MD5", 16),

  @SerialName("sha1")
  SHA_1("sha1", "SHA-1", 20),

  @SerialName("sha256")
  SHA_256("sha256", "SHA-256", 32),

  @SerialName("sha512")
  SHA_512("sha512", "SHA-512", 64);

  companion object {
    /** The algorithm called [name], ignoring case and dashes (`SHA-256`), or `null`. */
    fun named(name: String): ChecksumAlgorithm? {
      val key = name.trim().replace("-", "").lowercase()
      return entries.firstOrNull { it.id == key }
    }
  }
}

/**
 * A file's expected or verified digest, written and serialized as `<algorithm>:<hex>`, such as
 * `sha256:e3b0c442…`.
 *
 * @property value the digest as lowercase hex
 */
@Serializable(with = ChecksumSerializer::class)
data class Checksum(
  val algorithm: ChecksumAlgorithm,
  val value: String,
) {
  init {
    require(value.length == algorithm.byteCount * 2 && value.all { it in HEX_DIGITS }) {
      "A ${algorithm.label} checksum is ${algorithm.byteCount * 2} lowercase hex digits"
    }
  }

  /** `<algorithm>:<hex>`. */
  override fun toString(): String = "${algorithm.id}:$value"

  /** Whether the [digest] bytes match. */
  fun matches(digest: ByteArray): Boolean = value == digest.toHex()

  companion object {
    private const val HEX_DIGITS = "0123456789abcdef"

    /** The checksum of [digest] bytes. */
    fun of(algorithm: ChecksumAlgorithm, digest: ByteArray): Checksum {
      require(digest.size == algorithm.byteCount) {
        "A ${algorithm.label} digest is ${algorithm.byteCount} bytes"
      }
      return Checksum(algorithm, digest.toHex())
    }

    /**
     * Reads `<algorithm>:<value>` or `<algorithm>=<value>`, the value in hex or base64 (as in
     * `Repr-Digest`), or a bare hex digest whose length names the algorithm.
     *
     * @throws IllegalArgumentException when [text] is not a checksum of a supported algorithm
     */
    fun parse(text: String): Checksum {
      val trimmed = text.trim()
      val separator = trimmed.indexOfFirst { it == ':' || it == '=' }
      if (separator < 0) {
        val algorithm = ChecksumAlgorithm.entries.firstOrNull { it.byteCount * 2 == trimmed.length }
        requireNotNull(algorithm) {
          "A checksum is <algorithm>:<value>, or a hex digest of 32, 40, 64 or 128 digits"
        }
        return parse(algorithm, trimmed)
      }
      val name = trimmed.substring(0, separator)
      val algorithm = requireNotNull(ChecksumAlgorithm.named(name)) {
        "Unsupported checksum algorithm; use md5, sha1, sha256 or sha512"
      }
      return parse(algorithm, trimmed.substring(separator + 1))
    }

    /** [parse], or `null` when [text] is not a checksum. */
    fun parseOrNull(text: String): Checksum? = try {
      parse(text)
    } catch (_: IllegalArgumentException) {
      null
    }

    /**
     * Reads an [algorithm] digest in hex or base64.
     *
     * @throws IllegalArgumentException when [value] is neither
     */
    fun parse(algorithm: ChecksumAlgorithm, value: String): Checksum {
      val trimmed = value.trim()
      if (trimmed.length == algorithm.byteCount * 2 && trimmed.all { it.isHexDigit() }) {
        return Checksum(algorithm, trimmed.lowercase())
      }
      val encoded = trimmed.removeSurrounding(":")
      val bytes = BASE64_DECODERS.firstNotNullOfOrNull { decoder ->
        try {
          decoder.decode(encoded)
        } catch (_: IllegalArgumentException) {
          null
        }
      }
      require(bytes != null && bytes.size == algorithm.byteCount) {
        "A ${algorithm.label} checksum is ${algorithm.byteCount * 2} hex digits or " +
          "${algorithm.byteCount} bytes in base64"
      }
      return of(algorithm, bytes)
    }

    private val BASE64_DECODERS = listOf(
      Base64.Default.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL),
      Base64.UrlSafe.withPadding(Base64.PaddingOption.PRESENT_OPTIONAL),
    )

    private fun Char.isHexDigit(): Boolean =
      this in '0'..'9' || this in 'a'..'f' || this in 'A'..'F'

    private fun ByteArray.toHex(): String = buildString(size * 2) {
      for (byte in this@toHex) {
        val value = byte.toInt() and 0xff
        append(HEX_DIGITS[value ushr 4])
        append(HEX_DIGITS[value and 0x0f])
      }
    }
  }
}

/** Serializes a [Checksum] as its `<algorithm>:<hex>` text. */
internal object ChecksumSerializer : KSerializer<Checksum> {
  override val descriptor: SerialDescriptor =
    PrimitiveSerialDescriptor("com.linroid.ketch.api.Checksum", PrimitiveKind.STRING)

  override fun serialize(encoder: Encoder, value: Checksum) {
    encoder.encodeString(value.toString())
  }

  override fun deserialize(decoder: Decoder): Checksum {
    val text = decoder.decodeString()
    return try {
      Checksum.parse(text)
    } catch (e: IllegalArgumentException) {
      throw SerializationException(e.message, e)
    }
  }
}
