package com.linroid.kdown.task

import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.file.PathSerializer
import com.linroid.kdown.segment.Segment
import kotlinx.io.files.Path
import kotlinx.serialization.Serializable
import kotlin.time.Instant

/**
 * A persistent record of a download task. Contains all information needed
 * to restore a download after a process restart, including server info
 * fields used for resume validation and segment-level progress.
 *
 * This is the data model used by [com.linroid.kdown.TaskStore].
 */
@Serializable
data class TaskRecord(
  val taskId: String,
  val request: DownloadRequest,
  @Serializable(with = PathSerializer::class)
  val destPath: Path,
  val state: TaskState = TaskState.PENDING,
  val totalBytes: Long = -1,
  val downloadedBytes: Long = 0,
  val errorMessage: String? = null,
  val acceptRanges: Boolean? = null,
  val etag: String? = null,
  val lastModified: String? = null,
  val segments: List<Segment>? = null,
  val createdAt: Instant,
  val updatedAt: Instant
)
