package com.linroid.kdown.endpoints

import io.ktor.resources.Resource
import kotlinx.serialization.Serializable

/**
 * Type-safe Ktor Resource definitions for the KDown REST API.
 *
 * These resources are shared between the server and remote client
 * to ensure endpoint paths are defined in a single place.
 *
 * ## Endpoints
 *
 * ### Server
 * - `GET  /api/status`       — server health and task counts
 * - `PUT  /api/speed-limit`  — set global speed limit
 * - `POST /api/resolve`      — resolve URL metadata without downloading
 *
 * ### Tasks
 * - `GET    /api/tasks`                  — list all tasks
 * - `POST   /api/tasks`                  — create a new download
 * - `GET    /api/tasks/{id}`             — get task by ID
 * - `POST   /api/tasks/{id}/pause`       — pause a download
 * - `POST   /api/tasks/{id}/resume`      — resume a download
 * - `POST   /api/tasks/{id}/cancel`      — cancel a download
 * - `DELETE /api/tasks/{id}`             — remove a task
 * - `PUT    /api/tasks/{id}/speed-limit` — set task speed limit
 * - `PUT    /api/tasks/{id}/priority`    — set task priority
 *
 * ### Events (SSE)
 * - `GET /api/events`       — SSE stream of all task events
 * - `GET /api/events/{id}`  — SSE stream for a specific task
 */
@Serializable
@Resource("/api")
class Api {

  @Serializable
  @Resource("status")
  data class Status(val parent: Api = Api())

  @Serializable
  @Resource("speed-limit")
  data class SpeedLimit(val parent: Api = Api())

  @Serializable
  @Resource("resolve")
  data class Resolve(val parent: Api = Api())

  @Serializable
  @Resource("tasks")
  data class Tasks(val parent: Api = Api()) {

    @Serializable
    @Resource("{id}")
    data class ById(
      val parent: Tasks = Tasks(),
      val id: String,
    ) {

      @Serializable
      @Resource("pause")
      data class Pause(val parent: ById)

      @Serializable
      @Resource("resume")
      data class Resume(val parent: ById)

      @Serializable
      @Resource("cancel")
      data class Cancel(val parent: ById)

      @Serializable
      @Resource("speed-limit")
      data class SpeedLimit(val parent: ById)

      @Serializable
      @Resource("priority")
      data class Priority(val parent: ById)
    }
  }

  @Serializable
  @Resource("events")
  data class Events(val parent: Api = Api()) {

    @Serializable
    @Resource("{id}")
    data class ById(
      val parent: Events = Events(),
      val id: String,
    )
  }
}
