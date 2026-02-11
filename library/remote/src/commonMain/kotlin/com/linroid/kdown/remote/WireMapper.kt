package com.linroid.kdown.remote

import com.linroid.kdown.api.DownloadPriority
import com.linroid.kdown.api.DownloadProgress
import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.KDownError
import com.linroid.kdown.api.Segment
import com.linroid.kdown.api.SpeedLimit
import kotlinx.io.files.Path
import kotlin.time.Instant

internal object WireMapper {

  fun toDownloadRequest(wire: WireTaskResponse): DownloadRequest {
    return DownloadRequest(
      url = wire.url,
      directory = Path(wire.directory),
      fileName = wire.fileName,
      connections = 1,
      speedLimit = if (wire.speedLimitBytesPerSecond > 0) {
        SpeedLimit.of(wire.speedLimitBytesPerSecond)
      } else {
        SpeedLimit.Unlimited
      },
      priority = parsePriority(wire.priority)
    )
  }

  fun toCreateWire(request: DownloadRequest): WireCreateDownloadRequest {
    return WireCreateDownloadRequest(
      url = request.url,
      directory = request.directory.toString(),
      fileName = request.fileName,
      connections = request.connections,
      headers = request.headers,
      priority = request.priority.name,
      speedLimitBytesPerSecond =
        request.speedLimit.bytesPerSecond
    )
  }

  fun toDownloadState(wire: WireTaskResponse): DownloadState {
    return toDownloadState(
      wire.state,
      wire.progress,
      wire.error,
      wire.filePath
    )
  }

  fun toDownloadState(
    state: String,
    progress: WireProgressResponse?,
    error: String?,
    filePath: String?
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
        Path(filePath ?: "")
      )
      "failed" -> DownloadState.Failed(
        KDownError.Unknown(cause = Exception(error ?: "Unknown"))
      )
      "canceled" -> DownloadState.Canceled
      else -> DownloadState.Pending
    }
  }

  fun toSegments(
    wireSegments: List<WireSegmentResponse>
  ): List<Segment> {
    return wireSegments.map { wire ->
      Segment(
        index = wire.index,
        start = wire.start,
        end = wire.end,
        downloadedBytes = wire.downloadedBytes
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

  private fun parsePriority(value: String): DownloadPriority {
    return try {
      DownloadPriority.valueOf(value.uppercase())
    } catch (_: IllegalArgumentException) {
      DownloadPriority.NORMAL
    }
  }

  private fun WireProgressResponse.toDownloadProgress() =
    DownloadProgress(
      downloadedBytes = downloadedBytes,
      totalBytes = totalBytes,
      bytesPerSecond = bytesPerSecond
    )
}
