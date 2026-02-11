package com.linroid.kdown.embedded

import com.linroid.kdown.DownloadProgress
import com.linroid.kdown.DownloadState
import com.linroid.kdown.api.model.ProgressResponse
import com.linroid.kdown.api.model.TaskEvent
import com.linroid.kdown.task.DownloadTask

internal object TaskMapper {

  fun toEvent(
    task: DownloadTask,
    eventType: String
  ): TaskEvent {
    val state = task.state.value
    return TaskEvent(
      taskId = task.taskId,
      type = eventType,
      state = stateToString(state),
      progress = extractProgress(state)
        ?.let(::toProgressResponse),
      error = extractError(state),
      filePath = extractFilePath(state)
    )
  }

  private fun toProgressResponse(
    progress: DownloadProgress
  ): ProgressResponse {
    return ProgressResponse(
      downloadedBytes = progress.downloadedBytes,
      totalBytes = progress.totalBytes,
      percent = progress.percent,
      bytesPerSecond = progress.bytesPerSecond
    )
  }

  private fun stateToString(state: DownloadState): String {
    return when (state) {
      is DownloadState.Idle -> "idle"
      is DownloadState.Scheduled -> "scheduled"
      is DownloadState.Queued -> "queued"
      is DownloadState.Pending -> "pending"
      is DownloadState.Downloading -> "downloading"
      is DownloadState.Paused -> "paused"
      is DownloadState.Completed -> "completed"
      is DownloadState.Failed -> "failed"
      is DownloadState.Canceled -> "canceled"
    }
  }

  private fun extractProgress(
    state: DownloadState
  ): DownloadProgress? {
    return when (state) {
      is DownloadState.Downloading -> state.progress
      is DownloadState.Paused -> state.progress
      else -> null
    }
  }

  private fun extractError(state: DownloadState): String? {
    return when (state) {
      is DownloadState.Failed -> state.error.message
      else -> null
    }
  }

  private fun extractFilePath(state: DownloadState): String? {
    return when (state) {
      is DownloadState.Completed -> state.filePath.toString()
      else -> null
    }
  }
}
