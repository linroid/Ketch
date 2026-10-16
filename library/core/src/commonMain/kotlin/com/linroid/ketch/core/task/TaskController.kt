package com.linroid.ketch.core.task

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCondition
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.SpeedLimit

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
   * Saves whether the completed task is shared whenever its source can, with the [command] in the
   * task's retry ledger, in one record write under the task's [TaskHandle.controlLock]. Starting
   * or stopping the sharing itself is left to the caller.
   */
  suspend fun setSharing(
    handle: TaskHandle,
    sharing: Boolean,
    command: ControlCommand,
  ): ControlOutcome = throw UnsupportedOperationException("Sharing control is unavailable")
}
