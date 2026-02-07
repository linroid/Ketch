package com.linroid.kdown

import com.linroid.kdown.error.KDownError
import com.linroid.kdown.model.DownloadState
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.io.files.Path

class DownloadTask internal constructor(
  val taskId: String,
  val state: StateFlow<DownloadState>,
  private val pauseAction: suspend () -> Unit,
  private val resumeAction: suspend () -> DownloadTask?,
  private val cancelAction: suspend () -> Unit
) {
  suspend fun pause() {
    pauseAction()
  }

  suspend fun resume(): DownloadTask? {
    return resumeAction()
  }

  suspend fun cancel() {
    cancelAction()
  }

  suspend fun await(): Result<Path> {
    val finalState = state.first { it.isTerminal }
    return when (finalState) {
      is DownloadState.Completed -> Result.success(finalState.filePath)
      is DownloadState.Failed -> Result.failure(finalState.error)
      is DownloadState.Canceled -> Result.failure(KDownError.Canceled)
      else -> Result.failure(KDownError.Unknown(null))
    }
  }
}
