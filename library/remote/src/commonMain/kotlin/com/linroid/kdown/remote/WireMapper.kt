package com.linroid.kdown.remote

import com.linroid.kdown.api.DownloadPriority
import com.linroid.kdown.api.DownloadProgress
import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.FileSelectionMode
import com.linroid.kdown.api.KDownError
import com.linroid.kdown.api.ResolvedSource
import com.linroid.kdown.api.Segment
import com.linroid.kdown.api.SourceFile
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.endpoints.model.CreateDownloadRequest
import com.linroid.kdown.endpoints.model.ProgressResponse
import com.linroid.kdown.endpoints.model.ResolveUrlResponse
import com.linroid.kdown.endpoints.model.SegmentResponse
import com.linroid.kdown.endpoints.model.SourceFileResponse
import com.linroid.kdown.endpoints.model.TaskResponse
import kotlin.time.Instant

internal object WireMapper {

  fun toDownloadRequest(wire: TaskResponse): DownloadRequest {
    return DownloadRequest(
      url = wire.url,
      directory = wire.directory,
      fileName = wire.fileName,
      connections = 1,
      speedLimit = if (wire.speedLimitBytesPerSecond > 0) {
        SpeedLimit.of(wire.speedLimitBytesPerSecond)
      } else {
        SpeedLimit.Unlimited
      },
      priority = parsePriority(wire.priority),
    )
  }

  fun toCreateWire(
    request: DownloadRequest,
  ): CreateDownloadRequest {
    return CreateDownloadRequest(
      url = request.url,
      directory = request.directory ?: "",
      fileName = request.fileName,
      connections = request.connections,
      headers = request.headers,
      priority = request.priority.name,
      speedLimitBytesPerSecond =
        request.speedLimit.bytesPerSecond,
      selectedFileIds = request.selectedFileIds,
      resolvedUrl = request.resolvedUrl?.let {
        toResolveUrlResponse(it)
      },
    )
  }

  fun toDownloadState(wire: TaskResponse): DownloadState {
    return toDownloadState(
      wire.state,
      wire.progress,
      wire.error,
      wire.filePath
    )
  }

  fun toDownloadState(
    state: String,
    progress: ProgressResponse?,
    error: String?,
    filePath: String?,
  ): DownloadState {
    return when (state) {
      "idle" -> DownloadState.Idle
      "scheduled" -> DownloadState.Queued
      "queued" -> DownloadState.Queued
      "pending" -> DownloadState.Pending
      "downloading" -> DownloadState.Downloading(
        progress?.toDownloadProgress()
          ?: DownloadProgress(0, 0)
      )
      "paused" -> DownloadState.Paused(
        progress?.toDownloadProgress()
          ?: DownloadProgress(0, 0)
      )
      "completed" -> DownloadState.Completed(
        filePath ?: ""
      )
      "failed" -> DownloadState.Failed(
        KDownError.Unknown(cause = Exception(error ?: "Unknown")),
      )
      "canceled" -> DownloadState.Canceled
      else -> DownloadState.Pending
    }
  }

  fun toSegments(
    wireSegments: List<SegmentResponse>,
  ): List<Segment> {
    return wireSegments.map { wire ->
      Segment(
        index = wire.index,
        start = wire.start,
        end = wire.end,
        downloadedBytes = wire.downloadedBytes,
      )
    }
  }

  fun parseCreatedAt(createdAt: String): Instant {
    return try {
      Instant.parse(createdAt)
    } catch (_: Exception) {
      Instant.fromEpochMilliseconds(0)
    }
  }

  fun toResolvedSource(wire: ResolveUrlResponse): ResolvedSource {
    return ResolvedSource(
      url = wire.url,
      sourceType = wire.sourceType,
      totalBytes = wire.totalBytes,
      supportsResume = wire.supportsResume,
      suggestedFileName = wire.suggestedFileName,
      maxSegments = wire.maxSegments,
      metadata = wire.metadata,
      files = wire.files.map(::toSourceFile),
      selectionMode = parseSelectionMode(wire.selectionMode),
    )
  }

  fun toResolveUrlResponse(resolved: ResolvedSource): ResolveUrlResponse {
    return ResolveUrlResponse(
      url = resolved.url,
      sourceType = resolved.sourceType,
      totalBytes = resolved.totalBytes,
      supportsResume = resolved.supportsResume,
      suggestedFileName = resolved.suggestedFileName,
      maxSegments = resolved.maxSegments,
      metadata = resolved.metadata,
      files = resolved.files.map(::toSourceFileResponse),
      selectionMode = resolved.selectionMode.name,
    )
  }

  fun toSourceFile(wire: SourceFileResponse): SourceFile {
    return SourceFile(
      id = wire.id,
      name = wire.name,
      size = wire.size,
      metadata = wire.metadata,
    )
  }

  fun toSourceFileResponse(file: SourceFile): SourceFileResponse {
    return SourceFileResponse(
      id = file.id,
      name = file.name,
      size = file.size,
      metadata = file.metadata,
    )
  }

  private fun parseSelectionMode(value: String): FileSelectionMode {
    return try {
      FileSelectionMode.valueOf(value.uppercase())
    } catch (_: IllegalArgumentException) {
      FileSelectionMode.MULTIPLE
    }
  }

  private fun parsePriority(value: String): DownloadPriority {
    return try {
      DownloadPriority.valueOf(value.uppercase())
    } catch (_: IllegalArgumentException) {
      DownloadPriority.NORMAL
    }
  }

  private fun ProgressResponse.toDownloadProgress() =
    DownloadProgress(
      downloadedBytes = downloadedBytes,
      totalBytes = totalBytes,
      bytesPerSecond = bytesPerSecond,
    )
}
