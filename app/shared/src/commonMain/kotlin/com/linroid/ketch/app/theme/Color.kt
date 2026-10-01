package com.linroid.ketch.app.theme

import androidx.compose.ui.graphics.Color
import com.linroid.ketch.api.DownloadState

data class StateColorPair(
  val foreground: Color,
  val background: Color,
)

data class DownloadStateColors(
  val downloading: StateColorPair,
  val queued: StateColorPair,
  val scheduled: StateColorPair,
  val paused: StateColorPair,
  val completed: StateColorPair,
  val failed: StateColorPair,
  val canceled: StateColorPair,
) {
  fun forState(state: DownloadState): StateColorPair {
    return when (state) {
      is DownloadState.Downloading -> downloading
      is DownloadState.Queued -> queued
      is DownloadState.Scheduled -> scheduled
      is DownloadState.Paused -> paused
      is DownloadState.Completed -> completed
      is DownloadState.Failed -> failed
      is DownloadState.Canceled -> canceled
    }
  }
}

@Deprecated("Use KetchTheme.colors.status.")
val DarkStateColors: DownloadStateColors = darkKetchColors().toDownloadStateColors()

@Deprecated("Use KetchTheme.colors.status.")
val LightStateColors: DownloadStateColors = lightKetchColors().toDownloadStateColors()

internal fun KetchColors.toDownloadStateColors(): DownloadStateColors {
  fun pair(color: KetchStatusColor) = StateColorPair(color.color, color.soft)
  return DownloadStateColors(
    downloading = StateColorPair(accent, accentSoft),
    queued = pair(status.queued),
    scheduled = pair(status.scheduled),
    paused = pair(status.paused),
    completed = pair(status.completed),
    failed = pair(status.failed),
    canceled = pair(status.canceled),
  )
}
