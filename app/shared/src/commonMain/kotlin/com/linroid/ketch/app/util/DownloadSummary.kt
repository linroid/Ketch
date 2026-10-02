package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState

/**
 * Size details shown under a download's name: the bytes transferred so far, or the size, time
 * spent and average speed of a finished download.
 */
fun transferSummary(state: DownloadState): List<String> = when (state) {
  is DownloadState.Downloading -> listOfNotNull(progressSize(state.progress))
  is DownloadState.Paused -> listOfNotNull(progressSize(state.progress))
  is DownloadState.Completed -> completedSummary(state)
  else -> emptyList()
}

private fun progressSize(progress: DownloadProgress): String? = when {
  progress.totalBytes > 0 ->
    "${formatBytes(progress.downloadedBytes)} / ${formatBytes(progress.totalBytes)}"
  progress.downloadedBytes > 0 -> formatBytes(progress.downloadedBytes)
  else -> null
}

private fun completedSummary(state: DownloadState.Completed): List<String> {
  val size = state.totalBytes
  val time = state.downloadTime
  return buildList {
    if (size != null) add(formatBytes(size))
    if (time != null) add("took ${formatDuration(time)}")
    val average = if (size != null && time != null) averageSpeed(size, time) else null
    if (average != null) add("avg ${formatBytes(average)}/s")
  }
}
