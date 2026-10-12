package com.linroid.ketch.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/**
 * A download: its state and the commands that change it.
 *
 * @property request the task's request, including changes made through this task
 * @property requestState [request] as it changes
 * @property segments the byte ranges being downloaded, with their progress
 */
@SubclassOptInRequired(KetchImplementationApi::class)
interface DownloadTask {
  val taskId: String
  val request: DownloadRequest
  val requestState: StateFlow<DownloadRequest>
  val createdAt: Instant
  val state: StateFlow<DownloadState>
  val segments: StateFlow<List<Segment>>

  /**
   * 1-based place in the download queue (by priority, then age) of a [DownloadState.Queued] or
   * [PauseReason.Preempted] task; `null` when it does not wait for a slot, including a queued
   * task that already holds one, or when [KetchFeatures.QUEUE_POSITION] is not listed. A task
   * held back by [DownloadConfig.maxConnectionsPerHost] keeps its place while later ones start.
   * Separate from [state], so the two may briefly disagree.
   */
  val queuePosition: StateFlow<Int?> get() = NoQueuePosition

  /**
   * The file, or folder for multi-file sources, the download saves to; `null` until chosen and
   * from remote tasks. A [resume] with a destination can change it.
   */
  val outputPath: String? get() = null

  /**
   * Pauses the download, keeping its progress. A queued or [preempted][PauseReason.Preempted]
   * task leaves the queue until [resume].
   */
  suspend fun pause()

  /**
   * Resumes a paused or failed download from where it left off; a
   * [preempted][PauseReason.Preempted] task already waits and is left as it is.
   *
   * @param destination a new destination, such as an Android document URI that changed while
   *   naming the same file
   */
  suspend fun resume(destination: Destination? = null)

  /** Cancels the download for good. */
  suspend fun cancel()

  /**
   * Sets [DownloadRequest.speedLimit], live while downloading. The global limit still applies.
   */
  suspend fun setSpeedLimit(limit: SpeedLimit)

  /**
   * Sets [DownloadRequest.priority], reordering the queue; [DownloadPriority.URGENT] may preempt
   * a running task. Priority never allocates bandwidth or bypasses schedules and conditions.
   */
  suspend fun setPriority(priority: DownloadPriority)

  /**
   * Sets [DownloadRequest.connections]. A running download re-splits its segments, keeping
   * their progress.
   *
   * @param connections the count, or 0 for the run's default
   * @throws IllegalArgumentException if [connections] is negative
   * @throws UnsupportedOperationException for 0 without [KetchFeatures.AUTO_CONNECTIONS]
   */
  suspend fun setConnections(connections: Int)

  /**
   * Chooses which of [ResolvedSource.files] to download, per its
   * [ResolvedSource.selectionMode]. A running download switches without reconnecting (unchosen
   * files stay on disk); a task [awaiting a selection][PauseReason.AwaitingFileSelection]
   * starts; a completed task that gains files downloads them, while dropping files only changes
   * its size. Other states use it when they start.
   *
   * @param fileIds 1 to [MAX_SELECTED_FILES] IDs, each at most [MAX_FILE_ID_LENGTH] characters
   * @throws IllegalArgumentException if [fileIds] is empty, too many for the mode, or unknown
   * @throws IllegalStateException if the files are not known yet, the task was canceled, or a
   *   file Ketch does not own is in the way
   * @throws UnsupportedOperationException if there are no files to choose or the backend lacks
   *   [KetchFeatures.FILE_SELECTION]
   */
  suspend fun selectFiles(fileIds: Set<String>) {
    throw UnsupportedOperationException("Changing the files of this download is unavailable")
  }

  /**
   * Pauses the task, keeping its progress, and starts it again per [schedule] and
   * [conditions]. Ignored in a [terminal][DownloadState.isTerminal] state. The schedule is saved;
   * the conditions are not.
   *
   * @throws UnsupportedOperationException if the task cannot be rescheduled, such as a remote one
   */
  suspend fun reschedule(
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition> = emptyList(),
  )

  /**
   * Cancels the download and forgets the task.
   *
   * @param deleteFiles also delete its partial or finished files, best-effort
   */
  suspend fun remove(deleteFiles: Boolean = false)

  /**
   * Waits for a terminal state: the output path on completion, else a [KetchError].
   */
  suspend fun await(): Result<String> {
    val finalState = state.first { it.isTerminal }
    return when (finalState) {
      is DownloadState.Completed -> Result.success(finalState.outputPath)
      is DownloadState.Failed -> Result.failure(finalState.error)
      is DownloadState.Canceled -> Result.failure(KetchError.Canceled())
      else -> Result.failure(KetchError.Unknown(null))
    }
  }

  companion object {
    /** The most files one [selectFiles] call may choose. */
    const val MAX_SELECTED_FILES: Int = 100_000

    /** The longest file ID [selectFiles] accepts. */
    const val MAX_FILE_ID_LENGTH: Int = 128
  }
}

private val NoQueuePosition: StateFlow<Int?> = MutableStateFlow<Int?>(null).asStateFlow()
