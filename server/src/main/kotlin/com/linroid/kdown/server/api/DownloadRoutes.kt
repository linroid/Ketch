package com.linroid.kdown.server.api

import com.linroid.kdown.api.DownloadPriority
import com.linroid.kdown.api.DownloadRequest
import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.server.TaskMapper
import com.linroid.kdown.server.model.CreateDownloadRequest
import com.linroid.kdown.server.model.ErrorResponse
import com.linroid.kdown.server.model.PriorityRequest
import com.linroid.kdown.server.model.SpeedLimitRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.routing.delete
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.put
import io.ktor.server.routing.route
import kotlinx.io.files.Path

/**
 * Installs the `/api/downloads` REST routes for managing tasks.
 */
internal fun Route.downloadRoutes(kdown: KDownApi) {
  route("/api/downloads") {
    get {
      val tasks = kdown.tasks.value
      call.respond(tasks.map(TaskMapper::toResponse))
    }

    post {
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
        directory = Path(body.directory),
        fileName = body.fileName,
        connections = body.connections,
        headers = body.headers,
        priority = priority,
        speedLimit = speedLimit
      )
      val task = kdown.download(request)
      call.respond(
        HttpStatusCode.Created,
        TaskMapper.toResponse(task)
      )
    }

    get("/{id}") {
      val taskId = call.parameters["id"]!!
      val task = kdown.tasks.value.find {
        it.taskId == taskId
      }
      if (task == null) {
        call.respond(
          HttpStatusCode.NotFound,
          ErrorResponse("not_found", "Task not found: $taskId")
        )
        return@get
      }
      call.respond(TaskMapper.toResponse(task))
    }

    post("/{id}/pause") {
      val taskId = call.parameters["id"]!!
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

    post("/{id}/resume") {
      val taskId = call.parameters["id"]!!
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

    post("/{id}/cancel") {
      val taskId = call.parameters["id"]!!
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

    delete("/{id}") {
      val taskId = call.parameters["id"]!!
      val task = kdown.tasks.value.find {
        it.taskId == taskId
      }
      if (task == null) {
        call.respond(
          HttpStatusCode.NotFound,
          ErrorResponse("not_found", "Task not found: $taskId")
        )
        return@delete
      }
      task.remove()
      call.respond(HttpStatusCode.NoContent)
    }

    put("/{id}/speed-limit") {
      val taskId = call.parameters["id"]!!
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

    put("/{id}/priority") {
      val taskId = call.parameters["id"]!!
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
}

private fun parsePriority(value: String): DownloadPriority? {
  return try {
    DownloadPriority.valueOf(value.uppercase())
  } catch (_: IllegalArgumentException) {
    null
  }
}
