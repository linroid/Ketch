package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * A byte range of a download, fetched alongside the others.
 *
 * @property start first byte offset, inclusive
 * @property end last byte offset, inclusive
 */
@Serializable
data class Segment(
  val index: Int,
  val start: Long,
  val end: Long,
  val downloadedBytes: Long = 0,
) {
  /** Bytes in this range. */
  val totalBytes: Long
    get() = end - start + 1

  /** The next offset to write. */
  val currentOffset: Long
    get() = start + downloadedBytes

  /** Whether every byte is downloaded. */
  val isComplete: Boolean
    get() = downloadedBytes >= totalBytes

  /** Bytes left to download. */
  val remainingBytes: Long
    get() = totalBytes - downloadedBytes
}
