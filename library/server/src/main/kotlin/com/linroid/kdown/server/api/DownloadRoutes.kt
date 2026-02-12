package com.linroid.kdown.server.api

import com.linroid.kdown.api.DownloadPriority
import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.endpoints.Api
import com.linroid.kdown.endpoints.model.CreateDownloadRequest
import com.linroid.kdown.endpoints.model.ErrorResponse
import com.linroid.kdown.endpoints.model.PriorityRequest
import com.linroid.kdown.endpoints.model.SpeedLimitRequest
import com.linroid.kdown.server.TaskMapper
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * Installs the `/api/tasks` REST routes for managing tasks.
 */
internal fun Route.downloadRoutes(kdown: KDownApi) {
  get<Api.Tasks> {
    val tasks = kdown.tasks.value
    call.respond(tasks.map(TaskMapper::toResponse))
  }

  post<Api.Tasks> {
    val body = call.receive<CreateDownloadRequest>()
    val priority = parsePriority(body.priority)
    if (priority == null) {
      call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse(
          "invalid_priority",
          "Priority must be one of: " +
            "LOW, NORMAL, HIGH, URGENT"
        )
      )
      return@post
    }
    val speedLimit = if (body.speedLimitBytesPerSecond > 0) {
      SpeedLimit.of(body.speedLimitBytesPerSecond)
    } else {
      SpeedLimit.Unlimited
    }
    val request = DownloadRequest(
      url = body.url,
      directory = body.directory,
      fileName = body.fileName,
      connections = body.connections,
      headers = body.headers,
      priority = priority,
      speedLimit = speedLimit,
    )
    val task = kdown.download(request)
    call.respond(
      HttpStatusCode.Created,
      TaskMapper.toResponse(task)
    )
  }

  get<Api.Tasks.ById> { resource ->
    val task = kdown.tasks.value.find {
      it.taskId == resource.id
    }
    if (task == null) {
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse(
          "not_found", "Task not found: ${resource.id}"
        )
      )
      return@get
    }
    call.respond(TaskMapper.toResponse(task))
  }

  post<Api.Tasks.ById.Pause> { resource ->
    val taskId = resource.parent.id
    val task = kdown.tasks.value.find {
      it.taskId == taskId
    }
    if (task == null) {
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse("not_found", "Task not found: $taskId")
      )
      return@post
    }
    task.pause()
    call.respond(TaskMapper.toResponse(task))
  }

  post<Api.Tasks.ById.Resume> { resource ->
    val taskId = resource.parent.id
    val task = kdown.tasks.value.find {
      it.taskId == taskId
    }
    if (task == null) {
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse("not_found", "Task not found: $taskId")
      )
      return@post
    }
    task.resume()
    call.respond(TaskMapper.toResponse(task))
  }

  post<Api.Tasks.ById.Cancel> { resource ->
    val taskId = resource.parent.id
    val task = kdown.tasks.value.find {
      it.taskId == taskId
    }
    if (task == null) {
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse("not_found", "Task not found: $taskId")
      )
      return@post
    }
    task.cancel()
    call.respond(TaskMapper.toResponse(task))
  }

  delete<Api.Tasks.ById> { resource ->
    val task = kdown.tasks.value.find {
      it.taskId == resource.id
    }
    if (task == null) {
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse(
          "not_found", "Task not found: ${resource.id}"
        )
      )
      return@delete
    }
    task.remove()
    call.respond(HttpStatusCode.NoContent)
  }

  put<Api.Tasks.ById.SpeedLimit> { resource ->
    val taskId = resource.parent.id
    val task = kdown.tasks.value.find {
      it.taskId == taskId
    }
    if (task == null) {
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse("not_found", "Task not found: $taskId")
      )
      return@put
    }
    val body = call.receive<SpeedLimitRequest>()
    val limit = if (body.bytesPerSecond > 0) {
      SpeedLimit.of(body.bytesPerSecond)
    } else {
      SpeedLimit.Unlimited
    }
    task.setSpeedLimit(limit)
    call.respond(TaskMapper.toResponse(task))
  }

  put<Api.Tasks.ById.Priority> { resource ->
    val taskId = resource.parent.id
    val task = kdown.tasks.value.find {
      it.taskId == taskId
    }
    if (task == null) {
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse("not_found", "Task not found: $taskId")
      )
      return@put
    }
    val body = call.receive<PriorityRequest>()
    val priority = parsePriority(body.priority)
    if (priority == null) {
      call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse(
          "invalid_priority",
          "Priority must be one of: " +
            "LOW, NORMAL, HIGH, URGENT"
        )
      )
      return@put
    }
    task.setPriority(priority)
    call.respond(TaskMapper.toResponse(task))
  }
}

private fun parsePriority(value: String): DownloadPriority? {
  return try {
    DownloadPriority.valueOf(value.uppercase())
  } catch (_: IllegalArgumentException) {
    null
  }
}
