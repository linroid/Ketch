package com.linroid.ketch.server.api

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.endpoints.model.TaskEvent
import com.linroid.ketch.server.TaskMapper
import io.ktor.server.routing.Route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json

private val json = Json { encodeDefaults = true }
private val log = KetchLogger("EventRoutes")

/**
 * Installs the `/api/events` SSE endpoint that streams real-time
 * download state, request configuration, and segment changes to connected clients.
 *
 * Events are sent for:
 * - [TaskEvent.TaskAdded]: a new task appears in the tasks list
 * - [TaskEvent.TaskRemoved]: a task is removed from the tasks list
 * - [TaskEvent.StateChanged]: state, settings, segments or queue position change while not
 *   downloading
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
    trackTaskState(task)
  }
}

private suspend fun ServerSSESession.trackTaskState(
  task: DownloadTask,
) {
  taskEvents(task).collect { sendEvent(it) }
}

/**
 * The events of [task] on one stream. Requests are shown without bulky metadata
 * ([TaskMapper.viewOf]); a [TaskEvent.Progress] leaves out a request this stream already sent,
 * while the first event and every [TaskEvent.StateChanged] carry it.
 */
internal fun taskEvents(task: DownloadTask): Flow<TaskEvent> = flow {
  var sent: DownloadRequest? = null
  var view: DownloadRequest? = null
  combine(
    task.state,
    task.requestState,
    task.segments,
    task.queuePosition,
  ) { state, request, segments, position ->
    TaskUpdate(state, request, segments, position)
  }.collect { update ->
    val request = update.request
    val changed = request !== sent
    if (changed) {
      sent = request
      view = TaskMapper.viewOf(request)
    }
    val event = when (val state = update.state) {
      // A downloading task never waits in the queue, so Progress carries no position.
      is DownloadState.Downloading -> TaskEvent.Progress(
        taskId = task.taskId,
        state = state,
        request = if (changed) view else null,
        segments = update.segments,
      )

      else -> TaskEvent.StateChanged(
        taskId = task.taskId,
        state = state,
        request = view,
        segments = update.segments,
        queuePosition = update.queuePosition,
      )
    }
    emit(event)
  }
}

private class TaskUpdate(
  val state: DownloadState,
  val request: DownloadRequest,
  val segments: List<Segment>,
  val queuePosition: Int?,
)

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
