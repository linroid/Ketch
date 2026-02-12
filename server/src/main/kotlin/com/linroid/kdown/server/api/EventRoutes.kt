package com.linroid.kdown.server.api

import com.linroid.kdown.api.DownloadState
import com.linroid.kdown.api.DownloadTask
import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.server.TaskMapper
import com.linroid.kdown.endpoints.model.TaskEvent
import io.ktor.server.routing.Route
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }

/**
 * Installs the `/api/events` SSE endpoint that streams real-time
 * download state changes to connected clients.
 *
 * Events are sent for:
 * - `task_added`: a new task appears in the tasks list
 * - `task_removed`: a task is removed from the tasks list
 * - `state_changed`: a task's state changes (includes progress)
 */
internal fun Route.eventRoutes(kdown: KDownApi) {
  sse("/api/events") {
    coroutineScope {
      val activeJobs = mutableMapOf<String, Job>()

      kdown.tasks.collect { tasks ->
        val currentIds = tasks.map { it.taskId }.toSet()
        val trackedIds = activeJobs.keys.toSet()

        // Cancel trackers for removed tasks
        for (removedId in trackedIds - currentIds) {
          activeJobs.remove(removedId)?.cancel()
          val event = TaskEvent(
            taskId = removedId,
            type = "task_removed",
            state = "removed",
          )
          send(ServerSentEvent(
            data = json.encodeToString(event),
            event = "task_removed",
            id = removedId,
          ))
        }

        // Start trackers for new tasks
        for (task in tasks) {
          if (task.taskId !in trackedIds) {
            sendTaskEvent(task, "task_added")
            activeJobs[task.taskId] = launch {
              trackTaskState(task)
            }
          }
        }
      }
    }
  }

  sse("/api/events/{id}") {
    val taskId = call.parameters["id"]!!
    val task = kdown.tasks.value.find { it.taskId == taskId }
    if (task == null) {
      // Send error and close
      val errorEvent = TaskEvent(
        taskId = taskId,
        type = "error",
        state = "not_found",
      )
      send(ServerSentEvent(
        data = json.encodeToString(errorEvent),
        event = "error",
      ))
      return@sse
    }
    sendTaskEvent(task, "state_changed")
    trackTaskState(task)
  }
}

private suspend fun io.ktor.server.sse.ServerSSESession.trackTaskState(
  task: DownloadTask,
) {
  task.state.collect { state ->
    val eventType = when (state) {
      is DownloadState.Downloading -> "progress"
      else -> "state_changed"
    }
    sendTaskEvent(task, eventType)
  }
}

private suspend fun io.ktor.server.sse.ServerSSESession.sendTaskEvent(
  task: DownloadTask,
  eventType: String,
) {
  val event = TaskMapper.toEvent(task, eventType)
  send(ServerSentEvent(
    data = json.encodeToString(event),
    event = eventType,
    id = task.taskId,
  ))
}
