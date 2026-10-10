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
   * task's [TaskHandle.controlLock] itself. The default refuses, as controllers without file
   * selection do.
   */
  suspend fun selectFiles(handle: TaskHandle, fileIds: Set<String>?): ControlOutcome =
    throw UnsupportedOperationException("Changing the files of this download is unavailable")
}

/**
 * What a control change left the task at.
 *
 * @property generation the task's selection generation
 * @property seeding the task's seeding intent
 * @property revision the controller revision the change was published at; `null` until the
 *   torrent controller publishes revisions
 * @property replayed whether an exact retry returned an earlier outcome
 */
internal class ControlOutcome(
  val generation: Long,
  val seeding: Boolean,
  val revision: TorrentRevision?,
  val replayed: Boolean,
)
