package com.linroid.kdown.model

import com.linroid.kdown.error.KDownError
import kotlinx.io.files.Path

sealed class DownloadState {
  data object Idle : DownloadState()
  data object Pending : DownloadState()
  data class Downloading(val progress: DownloadProgress) : DownloadState()
  data object Paused : DownloadState()
  data class Completed(val filePath: Path) : DownloadState()
  data class Failed(val error: KDownError) : DownloadState()
  data object Canceled : DownloadState()

  val isTerminal: Boolean
    get() = this is Completed || this is Failed || this is Canceled

  val isActive: Boolean
    get() = this is Pending || this is Downloading
}
