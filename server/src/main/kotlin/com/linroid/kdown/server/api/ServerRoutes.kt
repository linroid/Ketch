package com.linroid.kdown.server.api

import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.core.KDown
import com.linroid.kdown.server.model.ServerStatus
import com.linroid.kdown.server.model.SpeedLimitRequest
import io.ktor.server.response.respond
import io.ktor.server.request.receive
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.put
import io.ktor.server.routing.route

/**
 * Installs server-level endpoints: health check and global
 * speed limit management.
 */
internal fun Route.serverRoutes(kdown: KDownApi) {
  get("/api/status") {
    val tasks = kdown.tasks.value
    val active = tasks.count { it.state.value.isActive }
    call.respond(
      ServerStatus(
        version = KDown.VERSION,
        activeTasks = active,
        totalTasks = tasks.size
      )
    )
  }

  route("/api/speed-limit") {
    put {
      val body = call.receive<SpeedLimitRequest>()
      val limit = if (body.bytesPerSecond > 0) {
        SpeedLimit.of(body.bytesPerSecond)
      } else {
        SpeedLimit.Unlimited
      }
      kdown.setGlobalSpeedLimit(limit)
      call.respond(body)
    }
  }
}
