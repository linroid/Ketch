package com.linroid.kdown.embedded

import com.linroid.kdown.DownloadRequest
import com.linroid.kdown.DownloadState
import com.linroid.kdown.KDown
import com.linroid.kdown.SpeedLimit
import com.linroid.kdown.api.ConnectionState
import com.linroid.kdown.api.KDownService
import com.linroid.kdown.api.model.ServerStatus
import com.linroid.kdown.api.model.TaskEvent
import com.linroid.kdown.task.DownloadTask
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch

/**
 * Embedded in-process implementation of [KDownService] that delegates
 * to a [KDown] instance directly. No HTTP involved.
 */
class EmbeddedKDownService(
  private val kdown: KDown
) : KDownService {

  override val connectionState: StateFlow<ConnectionState> =
    MutableStateFlow(ConnectionState.Connected)

  override val backendLabel: String = "Embedded"

  override val tasks: StateFlow<List<DownloadTask>> = kdown.tasks

  override suspend fun download(
    request: DownloadRequest
  ): DownloadTask {
    return kdown.download(request)
  }

  override suspend fun setGlobalSpeedLimit(limit: SpeedLimit) {
    kdown.setSpeedLimit(limit)
  }

  override suspend fun getStatus(): ServerStatus {
    val tasks = kdown.tasks.value
    return ServerStatus(
      version = KDown.VERSION,
      activeTasks = tasks.count { it.state.value.isActive },
      totalTasks = tasks.size
    )
  }

  override fun events(): Flow<TaskEvent> = callbackFlow {
    val activeJobs = mutableMapOf<String, Job>()

    val listJob = launch {
      var previousIds = emptySet<String>()
      kdown.tasks.collect { taskList ->
        val currentIds = taskList.map { it.taskId }.toSet()

        for (id in previousIds - currentIds) {
          activeJobs.remove(id)?.cancel()
        }

        for (task in taskList) {
          if (task.taskId !in activeJobs) {
            activeJobs[task.taskId] = launch {
              var previousState: DownloadState? = null
              task.state.collect { state ->
                val eventType = if (previousState == null) {
                  "task_added"
                } else {
                  when (state) {
                    is DownloadState.Downloading -> "progress"
                    else -> "state_changed"
                  }
                }
                send(
                  TaskMapper.toEvent(task, eventType)
                )
                previousState = state
              }
            }
          }
        }
        previousIds = currentIds
      }
    }

    awaitClose {
      listJob.cancel()
      activeJobs.values.forEach { it.cancel() }
    }
  }

  override fun close() {
    kdown.close()
  }
}
