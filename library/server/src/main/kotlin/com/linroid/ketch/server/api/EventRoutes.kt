package com.linroid.ketch.server.api

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.endpoints.model.TaskEvent
import io.ktor.server.routing.Route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }
private val log = KetchLogger("EventRoutes")

/**
 * Installs the `/api/events` SSE endpoint that streams real-time
 * download state changes to connected clients.
 *
 * Events are sent for:
 * - [TaskEvent.TaskAdded]: a new task appears in the tasks list
 * - [TaskEvent.TaskRemoved]: a task is removed from the tasks list
 * - [TaskEvent.StateChanged]: a task's state changes
 * - [TaskEvent.Progress]: download progress update
 */
internal fun Route.eventRoutes(ketch: KetchApi) {
  sse("/api/events") {
    log.i { "SSE client connected to /api/events" }
    coroutineScope {
      val activeJobs = mutableMapOf<String, Job>()
      for (task in ketch.tasks.value) {
        log.d { "Task tracker started for taskId=${task.taskId}" }
        activeJobs[task.taskId] = launch { trackTaskState(task) }
      }
      ketch.tasks.collect { tasks ->
        val currentIds = tasks.map { it.taskId }.toSet()
        val trackedIds = activeJobs.keys.toSet()

        // Cancel trackers for removed tasks
        for (removedId in trackedIds - currentIds) {
          activeJobs.remove(removedId)?.cancel()
          log.d { "Task tracker removed for taskId=$removedId" }
          sendEvent(TaskEvent.TaskRemoved(taskId = removedId))
        }

        // Start trackers for new tasks
        for (task in tasks) {
          if (task.taskId !in trackedIds) {
            sendEvent(
              TaskEvent.TaskAdded(
                taskId = task.taskId,
                state = task.state.value,
              ),
            )
            log.d { "Task tracker started for taskId=${task.taskId}" }
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
    log.i { "SSE client connected to /api/events/$taskId" }
    val task = ketch.tasks.value.find { it.taskId == taskId }
    if (task == null) {
      log.w { "Task not found for SSE: taskId=$taskId" }
      sendEvent(TaskEvent.Error(taskId = taskId))
      return@sse
    }
    sendEvent(
      TaskEvent.StateChanged(
        taskId = task.taskId,
        state = task.state.value,
      ),
    )
    trackTaskState(task)
  }
}

private suspend fun ServerSSESession.trackTaskState(
  task: DownloadTask,
) {
  task.state.collect { state ->
    val event = when (state) {
      is DownloadState.Downloading -> TaskEvent.Progress(
        taskId = task.taskId,
        state = state,
      )
      else -> TaskEvent.StateChanged(
        taskId = task.taskId,
        state = state,
      )
    }
    sendEvent(event)
  }
}

private suspend fun ServerSSESession.sendEvent(event: TaskEvent) {
  log.d { "SSE event: ${event.eventType.value} taskId=${event.taskId}" }
  send(
    ServerSentEvent(
      data = json.encodeToString(event),
      event = event.eventType.value,
      id = event.taskId,
    ),
  )
}
