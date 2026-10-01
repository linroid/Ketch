package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit

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

/** Live speed and time left of a running download; empty in other states. */
fun speedSummary(state: DownloadState): List<String> {
  if (state !is DownloadState.Downloading) return emptyList()
  val p = state.progress
  if (p.bytesPerSecond <= 0) return listOf("--")
  val speed = "${formatBytes(p.bytesPerSecond)}/s"
  if (p.totalBytes <= 0) return listOf(speed)
  val eta = formatEta((p.totalBytes - p.downloadedBytes).coerceAtLeast(0) / p.bytesPerSecond)
  return if (eta.isEmpty()) listOf(speed) else listOf(speed, "$eta left")
}

/** The task's speed limit while it is running, or `null` when it is not limited. */
fun speedLimitLabel(state: DownloadState, speedLimit: SpeedLimit): String? {
  if (state !is DownloadState.Downloading || speedLimit.isUnlimited) return null
  return "limit ${formatBytes(speedLimit.bytesPerSecond)}/s"
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
