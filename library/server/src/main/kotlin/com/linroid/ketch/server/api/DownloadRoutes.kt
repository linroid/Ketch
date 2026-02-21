package com.linroid.ketch.server.api

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.ConnectionsRequest
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.PriorityRequest
import com.linroid.ketch.endpoints.model.SpeedLimitRequest
import com.linroid.ketch.endpoints.model.TasksResponse
import com.linroid.ketch.server.TaskMapper
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

private val log = KetchLogger("DownloadRoutes")

/**
 * Installs the `/api/tasks` REST routes for managing tasks.
 */
internal fun Route.downloadRoutes(ketch: KetchApi) {
  get<Api.Tasks> {
    log.d { "GET /api/tasks" }
    val tasks = ketch.tasks.value
    call.respond(TasksResponse(tasks.map(TaskMapper::toSnapshot)))
  }

  post<Api.Tasks> {
    val request = call.receive<DownloadRequest>()
    log.d { "POST /api/tasks url=${request.url}" }
    val task = ketch.download(request)
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
    task.resume(resource.destination?.let { Destination(it) })
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
    log.d { "DELETE /api/tasks/${resource.id}" }
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
    task.remove()
    log.d { "Removed taskId=${resource.id}" }
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
    val request = call.receive<SpeedLimitRequest>()
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
    val request = call.receive<PriorityRequest>()
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
    val body = call.receive<ConnectionsRequest>()
    if (body.connections < 1) {
      call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse(
          "invalid_connections",
          "Connections must be greater than 0",
        ),
      )
      return@put
    }
    task.setConnections(body.connections)
    log.d { "Connections set for taskId=$taskId: ${body.connections}" }
    call.respond(TaskMapper.toSnapshot(task))
  }
}
