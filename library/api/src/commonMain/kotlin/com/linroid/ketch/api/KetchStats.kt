package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * Totals of the downloading tasks, which [Interest.Stats] reports. Byte counts cover tasks of
 * known size only.
 *
 * @property downloadBps their summed speed, in bytes per second
 * @property downloading how many tasks are downloading
 * @property unknownSizes how many of them have an unknown size
 */
@Serializable
data class KetchStats(
  val downloadBps: Long = 0,
  val downloadedBytes: Long = 0,
  val totalBytes: Long = 0,
  val remainingBytes: Long = 0,
  val downloading: Int = 0,
  val unknownSizes: Int = 0,
) {
  companion object {
    /** The totals of [states]. */
    fun of(states: Iterable<DownloadState>): KetchStats {
      var speed = 0L
      var downloaded = 0L
      var total = 0L
      var remaining = 0L
      var downloading = 0
      var unknownSizes = 0
      for (state in states) {
        val progress = (state as? DownloadState.Downloading)?.progress ?: continue
        downloading++
        speed += progress.bytesPerSecond
        if (progress.totalBytes > 0) {
          downloaded += progress.downloadedBytes
          total += progress.totalBytes
          remaining += (progress.totalBytes - progress.downloadedBytes).coerceAtLeast(0)
        } else {
          unknownSizes++
        }
      }
      return KetchStats(speed, downloaded, total, remaining, downloading, unknownSizes)
    }
  }
}
