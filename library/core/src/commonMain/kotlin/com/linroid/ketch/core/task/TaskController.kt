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
  suspend fun pause(taskId: String)
  suspend fun resume(handle: TaskHandle, destination: Destination? = null)
  suspend fun cancel(taskId: String)
  suspend fun remove(taskId: String)
  suspend fun setSpeedLimit(taskId: String, limit: SpeedLimit)
  suspend fun setConnections(taskId: String, connections: Int)
  suspend fun setPriority(taskId: String, priority: DownloadPriority)
  suspend fun reschedule(
    handle: TaskHandle,
    schedule: DownloadSchedule,
    conditions: List<DownloadCondition>,
  )
}
