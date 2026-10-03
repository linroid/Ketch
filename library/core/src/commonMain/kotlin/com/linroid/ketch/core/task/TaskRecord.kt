package com.linroid.ketch.core.task

import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.Segment
import com.linroid.ketch.core.engine.SourceResumeState
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Instant

/**
 * A persistent record of a download task. Contains all information needed
 * to restore a download after a process restart, including server info
 * fields used for resume validation and segment-level progress.
 *
 * This is the data model used by [TaskStore].
 *
 * @property downloadTime time spent downloading over every run of the task; `null` for records
 *   created before it was tracked, which keep it unknown rather than undercount it
 * @property completedAt when the task completed; set once, `null` before completion and for
 *   records completed before it was tracked
 */
@Serializable
data class TaskRecord(
  val taskId: String,
  val request: DownloadRequest,
  val outputPath: String? = null,
  val state: TaskState = TaskState.QUEUED,
  val totalBytes: Long = -1,
  val error: KetchError? = null,
  val segments: List<Segment>? = null,
  val sourceType: String? = null,
  val sourceResumeState: SourceResumeState? = null,
  val downloadTime: Duration? = null,
  val createdAt: Instant,
  val updatedAt: Instant,
  val completedAt: Instant? = null,
)

/** Progress saved in this record, as a paused task reports it. */
internal fun TaskRecord.savedProgress(): DownloadProgress =
  DownloadProgress(segments?.sumOf { it.downloadedBytes } ?: 0L, totalBytes)
