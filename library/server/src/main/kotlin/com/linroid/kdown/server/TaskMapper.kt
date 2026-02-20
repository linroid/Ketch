package com.linroid.kdown.server

import com.linroid.kdown.api.DownloadProgress
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.DownloadTask
import com.linroid.kdown.api.Segment
import com.linroid.kdown.endpoints.model.ProgressResponse
import com.linroid.kdown.endpoints.model.SegmentResponse
import com.linroid.kdown.endpoints.model.TaskEvent
import com.linroid.kdown.endpoints.model.TaskResponse

internal object TaskMapper {

  fun toResponse(task: DownloadTask): TaskResponse {
    val state = task.state.value
    val segments = task.segments.value
    return TaskResponse(
      taskId = task.taskId,
      url = task.request.url,
      directory = task.request.directory ?: "",
      fileName = task.request.fileName,
      state = stateToString(state),
      progress = extractProgress(state)?.let(::toProgressResponse),
      error = extractError(state),
      filePath = extractFilePath(state),
      segments = segments.map(::toSegmentResponse),
      createdAt = task.createdAt.toString(),
      priority = task.request.priority.name,
      speedLimitBytesPerSecond =
        task.request.speedLimit.bytesPerSecond,
      connections = task.request.connections,
    )
  }

  fun toEvent(
    task: DownloadTask,
    eventType: String,
  ): TaskEvent {
    val state = task.state.value
    return TaskEvent(
      taskId = task.taskId,
      type = eventType,
      state = stateToString(state),
      progress = extractProgress(state)?.let(::toProgressResponse),
      error = extractError(state),
      filePath = extractFilePath(state),
    )
  }

  fun toProgressResponse(
    progress: DownloadProgress,
  ): ProgressResponse {
    return ProgressResponse(
      downloadedBytes = progress.downloadedBytes,
      totalBytes = progress.totalBytes,
      percent = progress.percent,
      bytesPerSecond = progress.bytesPerSecond,
    )
  }

  fun toSegmentResponse(segment: Segment): SegmentResponse {
    return SegmentResponse(
      index = segment.index,
      start = segment.start,
      end = segment.end,
      downloadedBytes = segment.downloadedBytes,
      isComplete = segment.isComplete,
    )
  }

  fun stateToString(state: DownloadState): String {
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
    state: DownloadState,
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
      is DownloadState.Completed -> state.filePath
      else -> null
    }
  }
}
