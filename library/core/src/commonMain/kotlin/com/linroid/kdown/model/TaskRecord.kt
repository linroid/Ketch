package com.linroid.kdown.model

import kotlinx.io.files.Path
import kotlinx.serialization.Serializable

/**
 * A persistent record of a download task. Contains all information needed
 * to restore a download after a process restart.
 *
 * This is the data model used by [com.linroid.kdown.TaskStore] and is
 * distinct from [DownloadMetadata], which tracks segment-level progress.
 */
@Serializable
data class TaskRecord(
  val taskId: String,
  val url: String,
  @Serializable(with = PathSerializer::class)
  val destPath: Path,
  val connections: Int = 4,
  val headers: Map<String, String> = emptyMap(),
  val state: TaskState = TaskState.PENDING,
  val totalBytes: Long = -1,
  val downloadedBytes: Long = 0,
  val errorMessage: String? = null,
  val createdAt: Long,
  val updatedAt: Long
)
