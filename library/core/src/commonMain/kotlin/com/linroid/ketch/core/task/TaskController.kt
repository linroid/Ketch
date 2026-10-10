package com.linroid.ketch.core.task

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.torrent.TorrentRevision

/**
 * Internal mediator through which [RealDownloadTask] delegates all
 * task operations. Implemented by [com.linroid.ketch.core.Ketch]
 * to route calls to the appropriate engine components (coordinator,
 * queue, scheduler) and centralize multi-step cleanup sequences.
 */
internal interface TaskController {
  suspend fun pause(handle: TaskHandle)
  suspend fun resume(handle: TaskHandle, destination: Destination? = null)
  suspend fun cancel(handle: TaskHandle)
  suspend fun remove(handle: TaskHandle, deleteFiles: Boolean)
  suspend fun setSpeedLimit(taskId: String, limit: SpeedLimit)
  suspend fun setConnections(taskId: String, connections: Int)
  suspend fun setPriority(taskId: String, priority: DownloadPriority)
  suspend fun reschedule(
    handle: TaskHandle,
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition>,
  )

  /**
   * Chooses the task's files ([fileIds] `null` chooses every file), saving the selection, its
   * size and the next selection generation in one record write before applying it. Takes the
   * task's [TaskHandle.controlLock] itself. With a [command], the task's retry ledger answers an
   * exact retry and records the outcome in the same write. The default refuses, as controllers
   * without file selection do.
   */
  suspend fun selectFiles(
    handle: TaskHandle,
    fileIds: Set<String>?,
    command: ControlCommand? = null,
  ): ControlOutcome =
    throw UnsupportedOperationException("Changing the files of this download is unavailable")

  /**
   * Saves whether the completed task seeds whenever its source can, with the [command] in the
   * task's retry ledger, in one record write under the task's [TaskHandle.controlLock]. Starting
   * or stopping the seeding session is left to the caller.
   */
  suspend fun setSeeding(
    handle: TaskHandle,
    seeding: Boolean,
    command: ControlCommand,
  ): ControlOutcome = throw UnsupportedOperationException("Seeding control is unavailable")
}

/**
 * A torrent controller command, checked against the task's retry ledger before it changes
 * anything.
 *
 * @property key the caller's idempotency key; never logged
 * @property digest the SHA-256 of the canonical command, as 64 lowercase hex digits
 * @property precondition throws when the command must not run, such as
 *   [TorrentCommandError.CONFLICT][com.linroid.ketch.api.torrent.TorrentCommandError.CONFLICT]
 *   for a stale revision; it runs after an exact retry was answered from the ledger
 * @property reserve reserves the revision the outcome is published at, given the selection
 *   generation and the seeding intent the command leaves the task at
 */
internal class ControlCommand(
  val key: String,
  val digest: String,
  val precondition: suspend () -> Unit,
  val reserve: suspend (generation: Long, seeding: Boolean) -> TorrentRevision,
)

/**
 * What a control change left the task at.
 *
 * @property generation the task's selection generation
 * @property seeding the task's seeding intent
 * @property revision the controller revision the change was published at; `null` for changes
 *   made without a [ControlCommand]
 * @property replayed whether an exact retry returned an earlier outcome
 */
internal class ControlOutcome(
  val generation: Long,
  val seeding: Boolean,
  val revision: TorrentRevision?,
  val replayed: Boolean,
)
