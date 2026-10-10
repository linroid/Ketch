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
 * @property completedAt when the task last completed; `null` before completion, for records
 *   completed before it was tracked, and while a selection that added files reopens the task
 * @property control state of typed controls (torrent selections and seeding) that Ketch owns;
 *   `null` until the first control change
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
  val control: TaskControl? = null,
)

/**
 * Control state of sources with typed controls (torrents). Ketch owns it, never the sources, and
 * saves it with the change it describes in one [TaskStore.save].
 *
 * @property selectionGeneration counts the selection changes of the task; 0 until the first
 * @property seeding seed this completed task whenever its source can, including after a restart
 * @property commands the retry ledger of controller commands, at most [MAX_COMMANDS] entries
 */
@Serializable
data class TaskControl(
  val selectionGeneration: Long = 0,
  val seeding: Boolean = false,
  val commands: List<TaskCommandRecord> = emptyList(),
) {
  init {
    require(selectionGeneration >= 0) { "Selection generation must not be negative" }
    require(commands.size <= MAX_COMMANDS) { "Too many ledger entries" }
  }

  companion object {
    /** The most controller commands a task's retry ledger keeps. */
    const val MAX_COMMANDS: Int = 32
  }
}

/**
 * One controller command in a task's retry ledger, so an exact retry returns its outcome.
 *
 * @property key the caller's idempotency key; never logged
 * @property digest the SHA-256 of the canonical command, as 64 lowercase hex digits
 * @property epoch the revision epoch the outcome was published at
 * @property sequence the revision sequence the outcome was published at
 * @property selectionGeneration the selection generation after the command
 * @property seeding the seeding intent after a seeding command; `null` for other commands
 * @property recordedAtMs when the command ran, in epoch milliseconds
 */
@Serializable
data class TaskCommandRecord(
  val key: String,
  val digest: String,
  val epoch: String,
  val sequence: Long,
  val selectionGeneration: Long,
  val seeding: Boolean? = null,
  val recordedAtMs: Long,
) {
  init {
    require(key.length in 1..128) { "Invalid idempotency key" }
    require(digest.length == 64 && digest.all { it in '0'..'9' || it in 'a'..'f' }) {
      "Invalid command digest"
    }
    require(sequence >= 0 && selectionGeneration >= 0) { "Invalid revision" }
  }
}

/**
 * Whether this record is a task that waits for a file selection: it asked to
 * ([DownloadRequest.awaitFileSelection]), chose no files, and stopped once its source was known,
 * before it reserved an output path or saved any progress. A user pause while the file list is
 * still being looked up leaves no source type, so it stays a plain pause.
 */
internal fun TaskRecord.awaitsFileSelection(): Boolean = state == TaskState.PAUSED &&
  request.awaitFileSelection && request.selectedFileIds.isEmpty() && sourceType != null &&
  outputPath == null && segments == null

/** Progress saved in this record, as a paused task reports it. */
internal fun TaskRecord.savedProgress(): DownloadProgress =
  DownloadProgress(segments?.sumOf { it.downloadedBytes } ?: 0L, totalBytes)
