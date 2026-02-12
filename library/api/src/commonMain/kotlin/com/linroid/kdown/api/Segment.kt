package com.linroid.kdown.api

import kotlinx.serialization.Serializable

/**
 * Represents a byte-range segment of a download.
 *
 * A file is split into one or more segments that download concurrently.
 * Each segment tracks its own progress independently.
 *
 * @property index zero-based segment index
 * @property start inclusive start byte offset in the file
 * @property end inclusive end byte offset in the file
 * @property downloadedBytes number of bytes downloaded so far in this segment
 */
@Serializable
data class Segment(
  val index: Int,
  val start: Long,
  val end: Long,
  val downloadedBytes: Long = 0,
) {
  /** Total number of bytes this segment is responsible for. */
  val totalBytes: Long
    get() = end - start + 1

  /** The next byte offset to write at (`start + downloadedBytes`). */
  val currentOffset: Long
    get() = start + downloadedBytes

  /** `true` when [downloadedBytes] has reached [totalBytes]. */
  val isComplete: Boolean
    get() = downloadedBytes >= totalBytes

  /** Number of bytes still to be downloaded. */
  val remainingBytes: Long
    get() = totalBytes - downloadedBytes
}
