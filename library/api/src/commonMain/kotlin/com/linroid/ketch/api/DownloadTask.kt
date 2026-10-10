package com.linroid.ketch.api

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlin.time.Instant

/**
 * Represents a download task with reactive state and control methods.
 *
 * @property taskId Unique identifier for this download task
 * @property request The current download request configuration
 * @property requestState Observable download request configuration, including runtime changes
 * @property createdAt Timestamp when the task was created
 * @property state Observable download state
 * @property segments Observable list of download segments with their progress
 */
interface DownloadTask {
  val taskId: String
  val request: DownloadRequest
  val requestState: StateFlow<DownloadRequest>
  val createdAt: Instant
  val state: StateFlow<DownloadState>
  val segments: StateFlow<List<Segment>>

  /**
   * Where this task waits in its Ketch instance's download queue: 1 for the task the queue
   * starts next, 2 for the one after it, and so on. Tasks that wait are
   * [DownloadState.Queued], or [DownloadState.Paused] for [PauseReason.Preempted].
   *
   * `null` when the task does not wait for a slot: it runs, is scheduled, paused by the user,
   * finished, or holds a slot and is still starting (it is then [DownloadState.Queued] with a
   * `null` position). Also `null` from implementations that do not report positions, such as a
   * client of an older server; their [KetchStatus.features] lacks
   * [KetchFeatures.QUEUE_POSITION].
   *
   * Positions follow the queue's order, priority first, then age. A task whose site is at
   * [DownloadConfig.maxConnectionsPerHost] keeps its position while tasks behind it may start
   * first. Updated whenever the queue changes; it is a separate flow from [state], so an
   * observer can briefly see a new state with the previous position.
   */
  val queuePosition: StateFlow<Int?> get() = NoQueuePosition

  /**
   * Where this task saves its download, once the download has chosen it: the file, or the
   * folder of a source that writes several files, such as a torrent. It can change when the task
   * [resumes][resume] with a destination.
   *
   * `null` before the download has chosen it, and from implementations that do not report it,
   * such as tasks of a remote instance.
   */
  val outputPath: String? get() = null

  /**
   * Pauses the download, preserving segment progress for later resume.
   * Works while downloading, queued, or paused for [PauseReason.Preempted]; the task
   * then leaves the queue until [resume] is called.
   */
  suspend fun pause()

  /**
   * Resumes a paused or failed download from where it left off.
   * A task paused for [PauseReason.Preempted] already waits in the queue and is
   * left as it is.
   *
   * @param destination optionally override the download destination.
   *   This can be useful if the destination is obtained through
   *   Android's document provider framework, since the returned URI
   *   can change even when it points to the same file.
   */
  suspend fun resume(destination: Destination? = null)

  /** Cancels the download. This is a terminal action. */
  suspend fun cancel()

  /**
   * Updates the speed limit for this download task.
   * Persists the new limit in [request] and publishes it through [requestState].
   * Takes effect immediately while downloading; queued, scheduled, paused and failed
   * tasks use it when they start or resume. The global speed limit still applies,
   * see [DownloadRequest.speedLimit].
   *
   * @param limit the new speed limit, or [SpeedLimit.Unlimited] to remove
   */
  suspend fun setSpeedLimit(limit: SpeedLimit)

  /**
   * Updates the queue priority for this download task.
   * Persists the new priority and publishes it through [requestState].
   * Queued tasks are reordered; [DownloadPriority.URGENT] can pause a lower-priority
   * active task to start immediately, subject to concurrency and per-host limits.
   * Active tasks retain the new priority for future preemption decisions.
   * Priority does not allocate bandwidth or bypass schedules and conditions.
   *
   * @param priority the new priority level
   */
  suspend fun setPriority(priority: DownloadPriority)

  /**
   * Updates the number of concurrent connections (segments) for this
   * download task. Persists the new count in [request] and publishes it
   * through [requestState]. Takes effect immediately on active downloads —
   * segments are dynamically merged or split to match the new connection
   * count while preserving completed progress. Queued, scheduled, paused
   * and failed tasks use it when they start or resume. Servers without
   * HTTP Range or FTP REST support keep a single connection; BitTorrent
   * sources apply it as their peer connection limit.
   *
   * @param connections the new connection count, or 0 for Auto: the default of the
   *   configuration the current run started with ([DownloadConfig.maxConnectionsPerDownload],
   *   or a BitTorrent source's own default peer limit). Must not be negative.
   * @throws IllegalArgumentException if [connections] is negative.
   * @throws UnsupportedOperationException if the implementation cannot set Auto, such as a
   *   task of an older server; check [KetchFeatures.AUTO_CONNECTIONS].
   */
  suspend fun setConnections(connections: Int)

  /**
   * Changes which files of a download with several files (a torrent) this task downloads. Saves
   * the selection in [request] and its size as the task's total, then applies it: a running
   * download changes course without reconnecting (newly chosen files are created and downloaded,
   * unchosen ones stay on disk but stop downloading); queued, scheduled, paused and failed tasks
   * use it when they start; a task paused for [PauseReason.AwaitingFileSelection] starts; a
   * completed task whose selection gains files downloads them (Queued, Downloading, then
   * Completed again) while removing files only changes its size. Re-selecting the current files
   * does nothing.
   *
   * @param fileIds IDs from [ResolvedSource.files]: between 1 and [MAX_SELECTED_FILES] of them,
   *   each 1 to [MAX_FILE_ID_LENGTH] characters long
   * @throws IllegalArgumentException if [fileIds] is empty, has more than [MAX_SELECTED_FILES]
   *   entries, or names a file the download does not have
   * @throws IllegalStateException if the file list is not known yet, the task was canceled, or a
   *   file Ketch does not own exists where a newly chosen file would be saved
   * @throws UnsupportedOperationException if the download has no files to choose or the backend
   *   cannot change them (an older server; check [KetchFeatures.TORRENT_FILE_SELECTION])
   */
  suspend fun selectFiles(fileIds: Set<String>) {
    throw UnsupportedOperationException("Changing the files of this download is unavailable")
  }

  /**
   * Reschedules this download with a new schedule and optional conditions.
   * Active downloads are paused (preserving progress) before rescheduling.
   * Works from any non-terminal state; calls in a [terminal][DownloadState.isTerminal]
   * state (completed, failed or canceled) are ignored. The new schedule is persisted in
   * [request] and survives a restart; like [DownloadRequest.conditions],
   * the conditions themselves are not persisted.
   *
   * @param schedule the new schedule to apply
   * @param conditions optional conditions that must be met before starting
   * @throws UnsupportedOperationException if the implementation cannot
   *   reschedule, such as a task of a remote Ketch instance
   */
  suspend fun reschedule(
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition> = emptyList(),
  )

  /**
   * Cancels the download and removes it from the task store and tasks list.
   *
   * @param deleteFiles when `true`, also delete the downloaded data
   *   (partial or completed). Deletion is best-effort: failures are
   *   logged and do not prevent the task record from being removed.
   *   Defaults to `false` for backward compatibility.
   */
  suspend fun remove(deleteFiles: Boolean = false)

  /**
   * Suspends until the download reaches a terminal state.
   *
   * @return [Result.success] with the output file path on completion,
   *   or [Result.failure] with a [KetchError] on failure or cancellation
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
