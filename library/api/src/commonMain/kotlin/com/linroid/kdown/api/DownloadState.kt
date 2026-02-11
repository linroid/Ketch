package com.linroid.kdown.api

import kotlinx.io.files.Path

sealed class DownloadState {
  data object Idle : DownloadState()
  data class Scheduled(val schedule: DownloadSchedule) : DownloadState()
  data object Queued : DownloadState()
  data object Pending : DownloadState()
  data class Downloading(val progress: DownloadProgress) : DownloadState()
  data class Paused(val progress: DownloadProgress) : DownloadState()
  data class Completed(val filePath: Path) : DownloadState()
  data class Failed(val error: KDownError) : DownloadState()
  data object Canceled : DownloadState()

  val isTerminal: Boolean
    get() = this is Completed || this is Failed || this is Canceled

  val isActive: Boolean
    get() = this is Pending || this is Downloading
}
