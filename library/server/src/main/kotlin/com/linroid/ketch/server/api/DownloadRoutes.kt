package com.linroid.ketch.server.api

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.ConnectionsRequest
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.FileSelectionRequest
import com.linroid.ketch.endpoints.model.PriorityRequest
import com.linroid.ketch.endpoints.model.SpeedLimitRequest
import com.linroid.ketch.endpoints.model.TasksResponse
import com.linroid.ketch.server.DestinationGuard
import com.linroid.ketch.server.TaskMapper
import io.ktor.http.HttpStatusCode
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

private val log = KetchLogger("DownloadRoutes")

/**
 * Installs the `/api/tasks` REST routes for managing tasks. Paths callers choose, and files
 * removing a task deletes, go through [destinations].
 */
internal fun Route.downloadRoutes(ketch: KetchApi, destinations: DestinationGuard) {
  get<Api.Tasks> {
    log.d { "GET /api/tasks" }
    val tasks = ketch.tasks.value
    call.respond(TasksResponse(tasks.map(TaskMapper::toSnapshot)))
  }

  post<Api.Tasks> {
    val received = call.receiveJson<DownloadRequest>(MAX_TASK_BODY_BYTES)
    log.d { "POST /api/tasks url=${redactUrl(received.url)}" }
    val task = ketch.download(destinations.newDownload(received))
    call.respond(
      HttpStatusCode.Created,
      TaskMapper.toSnapshot(task),
    )
  }

  get<Api.Tasks.ById> { resource ->
    log.d { "GET /api/tasks/${resource.id}" }
    val task = ketch.tasks.value.find {
      it.taskId == resource.id
    }
    if (task == null) {
      log.w { "Task not found: taskId=${resource.id}" }
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse(
          "not_found", "Task not found: ${resource.id}",
        ),
      )
      return@get
    }
    call.respond(TaskMapper.toSnapshot(task))
  }

  post<Api.Tasks.ById.Pause> { resource ->
    val taskId = resource.parent.id
    log.d { "POST /api/tasks/$taskId/pause" }
    val task = ketch.tasks.value.find {
      it.taskId == taskId
    }
    if (task == null) {
      log.w { "Task not found: taskId=$taskId" }
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse("not_found", "Task not found: $taskId"),
      )
      return@post
    }
    task.pause()
    log.d { "Paused taskId=$taskId" }
    call.respond(TaskMapper.toSnapshot(task))
  }

  post<Api.Tasks.ById.Resume> { resource ->
    val taskId = resource.parent.id
    log.d { "POST /api/tasks/$taskId/resume" }
    val task = ketch.tasks.value.find {
      it.taskId == taskId
    }
    if (task == null) {
      log.w { "Task not found: taskId=$taskId" }
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse("not_found", "Task not found: $taskId"),
      )
      return@post
    }
    task.resume(resource.destination?.let { destinations.resumeDestination(it) })
    log.d { "Resumed taskId=$taskId" }
    call.respond(TaskMapper.toSnapshot(task))
  }

  post<Api.Tasks.ById.Cancel> { resource ->
    val taskId = resource.parent.id
    log.d { "POST /api/tasks/$taskId/cancel" }
    val task = ketch.tasks.value.find {
      it.taskId == taskId
    }
    if (task == null) {
      log.w { "Task not found: taskId=$taskId" }
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse("not_found", "Task not found: $taskId"),
      )
      return@post
    }
    task.cancel()
    log.d { "Canceled taskId=$taskId" }
    call.respond(TaskMapper.toSnapshot(task))
  }

  delete<Api.Tasks.ById> { resource ->
    val deleteFiles = call.request.queryParameters["deleteFiles"]
      ?.toBooleanStrictOrNull() ?: false
    log.d { "DELETE /api/tasks/${resource.id} deleteFiles=$deleteFiles" }
    val task = ketch.tasks.value.find {
      it.taskId == resource.id
    }
    if (task == null) {
      log.w { "Task not found: taskId=${resource.id}" }
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse(
          "not_found", "Task not found: ${resource.id}",
        ),
      )
      return@delete
    }
    if (deleteFiles) destinations.checkDeletion(task)
    task.remove(deleteFiles = deleteFiles)
    log.d { "Removed taskId=${resource.id} deleteFiles=$deleteFiles" }
    call.respond(HttpStatusCode.NoContent)
  }

  put<Api.Tasks.ById.SpeedLimit> { resource ->
    val taskId = resource.parent.id
    log.d { "PUT /api/tasks/$taskId/speed-limit" }
    val task = ketch.tasks.value.find {
      it.taskId == taskId
    }
    if (task == null) {
      log.w { "Task not found: taskId=$taskId" }
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse("not_found", "Task not found: $taskId"),
      )
      return@put
    }
    val request = call.receiveJson<SpeedLimitRequest>()
    task.setSpeedLimit(request.limit)
    log.d { "Speed limit set for taskId=$taskId: ${request.limit}" }
    call.respond(TaskMapper.toSnapshot(task))
  }

  put<Api.Tasks.ById.Priority> { resource ->
    val taskId = resource.parent.id
    log.d { "PUT /api/tasks/$taskId/priority" }
    val task = ketch.tasks.value.find {
      it.taskId == taskId
    }
    if (task == null) {
      log.w { "Task not found: taskId=$taskId" }
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse("not_found", "Task not found: $taskId"),
      )
      return@put
    }
    val request = call.receiveJson<PriorityRequest>()
    task.setPriority(request.priority)
    log.d { "Priority set for taskId=$taskId: ${request.priority}" }
    call.respond(TaskMapper.toSnapshot(task))
  }

  put<Api.Tasks.ById.Connections> { resource ->
    val taskId = resource.parent.id
    log.d { "PUT /api/tasks/$taskId/connections" }
    val task = ketch.tasks.value.find {
      it.taskId == taskId
    }
    if (task == null) {
      log.w { "Task not found: taskId=$taskId" }
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse("not_found", "Task not found: $taskId"),
      )
      return@put
    }
    val body = call.receiveJson<ConnectionsRequest>()
    // 0 means Auto (the task's default); older servers answered 400 for it, which
    // RemoteDownloadTask turns into UnsupportedOperationException.
    if (body.connections < 0) {
      call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse(
          "invalid_connections",
          "Connections must not be negative",
        ),
      )
      return@put
    }
    task.setConnections(body.connections)
    log.d { "Connections set for taskId=$taskId: ${body.connections}" }
    call.respond(TaskMapper.toSnapshot(task))
  }

  put<Api.Tasks.ById.Files> { resource ->
    val taskId = resource.parent.id
    log.d { "PUT /api/tasks/$taskId/files" }
    val task = ketch.tasks.value.find { it.taskId == taskId }
    if (task == null) {
      log.w { "Task not found: taskId=$taskId" }
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse("not_found", "Task not found: $taskId"),
      )
      return@put
    }
    val body = call.receiveJson<FileSelectionRequest>()
    val ids = body.fileIds
    if (ids.size !in 1..DownloadTask.MAX_SELECTED_FILES ||
      ids.any { it.length !in 1..DownloadTask.MAX_FILE_ID_LENGTH }
    ) {
      call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse(
          "invalid_selection",
          "Choose between 1 and ${DownloadTask.MAX_SELECTED_FILES} files, each with an ID " +
            "of 1 to ${DownloadTask.MAX_FILE_ID_LENGTH} characters",
        ),
      )
      return@put
    }
    val refusal = try {
      task.selectFiles(ids)
      null
    } catch (e: IllegalArgumentException) {
      HttpStatusCode.BadRequest to ErrorResponse(
        "invalid_selection", e.message ?: "Invalid file selection",
      )
    } catch (e: UnsupportedOperationException) {
      HttpStatusCode.NotImplemented to ErrorResponse(
        "unsupported", e.message ?: "Changing the files of this download is unavailable",
      )
    } catch (e: IllegalStateException) {
      HttpStatusCode.Conflict to ErrorResponse(
        "selection_unavailable", e.message ?: "The files of this download cannot change now",
      )
    }
    if (refusal != null) {
      log.d { "Refused the file selection of taskId=$taskId: ${refusal.second.error}" }
      call.respond(refusal.first, refusal.second)
      return@put
    }
    log.d { "Selected ${ids.size} file(s) of taskId=$taskId" }
    call.respond(TaskMapper.toSnapshot(task))
  }
}
