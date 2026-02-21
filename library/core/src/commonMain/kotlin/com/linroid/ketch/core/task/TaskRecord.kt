package com.linroid.ketch.core.task

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.engine.SourceResumeState
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A persistent record of a download task. Contains all information needed
 * to restore a download after a process restart, including server info
 * fields used for resume validation and segment-level progress.
 *
 * This is the data model used by [TaskStore].
 */
@Serializable
data class TaskRecord(
  val taskId: String,
  val request: DownloadRequest,
  val outputPath: String? = null,
  val state: TaskState = TaskState.QUEUED,
  val totalBytes: Long = -1,
  val downloadedBytes: Long = 0,
  val errorMessage: String? = null,
  val acceptRanges: Boolean? = null,
  val etag: String? = null,
  val lastModified: String? = null,
  val segments: List<Segment>? = null,
  val sourceType: String? = null,
  val sourceResumeState: SourceResumeState? = null,
  val createdAt: Instant,
  val updatedAt: Instant,
)
