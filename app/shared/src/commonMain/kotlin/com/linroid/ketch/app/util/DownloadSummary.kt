package com.linroid.ketch.app.util

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.durationText
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.speedText
import com.linroid.ketch.app.i18n.text
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.row_took
import ketch.app.shared.generated.resources.summary_average_speed
import ketch.app.shared.generated.resources.summary_size_of_total

/**
 * Size details shown under a download's name: the bytes transferred so far, or the size, time
 * spent and average speed of a finished download.
 */
fun transferSummary(state: DownloadState): List<UiText> = when (state) {
  is DownloadState.Downloading -> listOfNotNull(progressSize(state.progress))
  is DownloadState.Paused -> listOfNotNull(progressSize(state.progress))
  is DownloadState.Completed -> completedSummary(state)
  else -> emptyList()
}

private fun progressSize(progress: DownloadProgress): UiText? = when {
  progress.totalBytes > 0 -> Res.string.summary_size_of_total.text(
    sizeText(progress.downloadedBytes),
    sizeText(progress.totalBytes),
  )
  progress.downloadedBytes > 0 -> sizeText(progress.downloadedBytes)
  else -> null
}

private fun completedSummary(state: DownloadState.Completed): List<UiText> {
  val size = state.totalBytes
  val time = state.downloadTime
  return buildList {
    if (size != null) add(sizeText(size))
    if (time != null) add(Res.string.row_took.text(durationText(time)))
    val average = if (size != null && time != null) averageSpeed(size, time) else null
    if (average != null) add(Res.string.summary_average_speed.text(speedText(average)))
  }
}
