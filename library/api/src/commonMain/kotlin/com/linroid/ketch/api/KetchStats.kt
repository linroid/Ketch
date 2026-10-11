package com.linroid.ketch.api

import kotlinx.serialization.Serializable

/**
 * Totals of the tasks that are downloading, which [Interest.Stats] reports. Counts per state
 * follow from [KetchApi.tasks].
 *
 * @property downloadBps the summed download speed, in bytes per second
 * @property downloadedBytes bytes downloaded by the tasks of known size
 * @property totalBytes size of the tasks of known size
 * @property remainingBytes bytes the tasks of known size still have to download
 * @property downloading tasks that are downloading
 * @property unknownSizes tasks that are downloading content of unknown size
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
    /** The totals of the [states] of some tasks. */
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
