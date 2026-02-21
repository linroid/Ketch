package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadState

enum class StatusFilter(val label: String) {
  All("All"),
  Downloading("Downloading"),
  Completed("Completed"),
  Paused("Paused"),
  Failed("Failed");

  fun matches(state: DownloadState): Boolean = when (this) {
    All -> true
    Downloading -> state is DownloadState.Downloading ||
      state is DownloadState.Queued ||
      state is DownloadState.Scheduled
    Paused -> state is DownloadState.Paused
    Completed -> state is DownloadState.Completed
    Failed -> state is DownloadState.Failed ||
      state is DownloadState.Canceled
  }
}
