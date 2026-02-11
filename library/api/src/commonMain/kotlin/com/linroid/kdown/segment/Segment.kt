package com.linroid.kdown.segment

import kotlinx.serialization.Serializable

@Serializable
data class Segment(
  val index: Int,
  val start: Long,
  val end: Long,
  val downloadedBytes: Long = 0
) {
  val totalBytes: Long
    get() = end - start + 1

  val currentOffset: Long
    get() = start + downloadedBytes

  val isComplete: Boolean
    get() = downloadedBytes >= totalBytes

  val remainingBytes: Long
    get() = totalBytes - downloadedBytes
}
