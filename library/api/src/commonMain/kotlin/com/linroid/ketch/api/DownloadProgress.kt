package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * Snapshot of download progress aggregated across all segments.
 *
 * @property downloadedBytes total number of bytes received so far
 * @property totalBytes expected file size in bytes, or 0 if unknown
 * @property bytesPerSecond current download speed in bytes per second
 */
@Serializable
data class DownloadProgress(
  val downloadedBytes: Long,
  val totalBytes: Long,
  val bytesPerSecond: Long = 0,
) {
  /** Fraction complete in the range `0f..1f`, or `0f` if [totalBytes] is unknown. */
  val percent: Float
    get() = if (totalBytes > 0) downloadedBytes.toFloat() / totalBytes else 0f

  /** `true` when [downloadedBytes] has reached or exceeded [totalBytes]. */
  val isComplete: Boolean
    get() = totalBytes > 0 && downloadedBytes >= totalBytes
}
