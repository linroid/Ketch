package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * A download's progress over all segments.
 *
 * @property totalBytes the expected size, or 0 if unknown
 */
@Serializable
data class DownloadProgress(
  val downloadedBytes: Long,
  val totalBytes: Long,
  val bytesPerSecond: Long = 0,
) {
  /** Fraction done, `0f..1f`; `0f` when the size is unknown. */
  val percent: Float
    get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f

  /** Whether every byte of a known size is downloaded. */
  val isComplete: Boolean
    get() = totalBytes > 0 && downloadedBytes >= totalBytes
}
