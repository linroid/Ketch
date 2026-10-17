package com.linroid.ketch.core.mirror

import com.linroid.ketch.api.Checksum
import com.linroid.ketch.api.ChecksumAlgorithm

/**
 * One HTTP(S) URL serving the whole content, as a Metalink lists them.
 *
 * @property url an absolute `http` or `https` URL, which the caller has validated
 * @property priority lower is preferred; mirrors of equal priority keep their order
 * @property location the ISO 3166-1 country code of the mirror, if known; informational only
 */
data class Mirror(
  val url: String,
  val priority: Int = 0,
  val location: String? = null,
) {
  /** Leaves the URL out, as it may carry a token. */
  override fun toString(): String = "Mirror(priority=$priority, location=$location)"
}

/**
 * The digests of consecutive pieces of the content, so a piece a mirror corrupted can be fetched
 * again from another instead of the whole file.
 *
 * @property length bytes per piece; the last piece holds the rest
 * @property hashes the digest of each piece, in order, all of [algorithm]
 */
class PieceHashes(
  val algorithm: ChecksumAlgorithm,
  val length: Long,
  val hashes: List<Checksum>,
) {
  init {
    require(length > 0) { "A piece holds at least one byte" }
    require(hashes.all { it.algorithm == algorithm }) { "Every piece uses ${algorithm.label}" }
  }

  /** Whether these pieces describe exactly [totalBytes] bytes. */
  fun covers(totalBytes: Long): Boolean =
    totalBytes > 0 && hashes.size.toLong() == (totalBytes - 1) / length + 1
}
