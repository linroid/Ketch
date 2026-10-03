package com.linroid.ketch.endpoints.model

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Segment
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * Snapshot of a single download task.
 *
 * @property queuePosition [DownloadTask.queuePosition]; `null` when the task does not wait or
 *   the server is older
 */
@Serializable
data class TaskSnapshot(
  val taskId: String,
  val request: DownloadRequest,
  val state: DownloadState,
  val segments: List<Segment> = emptyList(),
  val createdAt: Instant,
  val queuePosition: Int? = null,
)
