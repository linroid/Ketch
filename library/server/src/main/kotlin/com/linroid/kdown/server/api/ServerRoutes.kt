package com.linroid.kdown.server.api

import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.api.KDownVersion
import com.linroid.kdown.api.SpeedLimit
import com.linroid.kdown.endpoints.Api
import com.linroid.kdown.endpoints.model.ResolveUrlRequest
import com.linroid.kdown.endpoints.model.ResolveUrlResponse
import com.linroid.kdown.endpoints.model.ServerStatus
import com.linroid.kdown.endpoints.model.SourceFileResponse
import com.linroid.kdown.endpoints.model.SpeedLimitRequest
import io.ktor.server.request.receive
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * Installs server-level endpoints: health check and global
 * speed limit management.
 */
internal fun Route.serverRoutes(kdown: KDownApi) {
  get<Api.Status> {
    val tasks = kdown.tasks.value
    val active = tasks.count { it.state.value.isActive }
    call.respond(
      ServerStatus(
        version = KDownVersion.DEFAULT,
        activeTasks = active,
        totalTasks = tasks.size,
      )
    )
  }

  put<Api.SpeedLimit> {
    val body = call.receive<SpeedLimitRequest>()
    val limit = if (body.bytesPerSecond > 0) {
      SpeedLimit.of(body.bytesPerSecond)
    } else {
      SpeedLimit.Unlimited
    }
    kdown.setGlobalSpeedLimit(limit)
    call.respond(body)
  }

  post<Api.Resolve> {
    val body = call.receive<ResolveUrlRequest>()
    val resolved = kdown.resolve(body.url, body.headers)
    call.respond(
      ResolveUrlResponse(
        url = resolved.url,
        sourceType = resolved.sourceType,
        totalBytes = resolved.totalBytes,
        supportsResume = resolved.supportsResume,
        suggestedFileName = resolved.suggestedFileName,
        maxSegments = resolved.maxSegments,
        metadata = resolved.metadata,
        files = resolved.files.map { file ->
          SourceFileResponse(
            id = file.id,
            name = file.name,
            size = file.size,
            metadata = file.metadata,
          )
        },
        selectionMode = resolved.selectionMode.name,
      )
    )
  }
}
