package com.linroid.ketch.server.api

import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.HealthResponse
import com.linroid.ketch.endpoints.model.HealthStatus
import io.ktor.http.HttpStatusCode
import io.ktor.server.resources.get
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

/**
 * Installs `GET /api/health`, which takes no access token so that health checks, such as the
 * Docker image's, can ask: `200` once [isReady], `503` while the server still restores the tasks
 * saved by earlier runs. It tells nothing else about the server.
 */
internal fun Route.healthRoutes(isReady: () -> Boolean) {
  get<Api.Health> {
    if (isReady()) {
      call.respond(HealthResponse(HealthStatus.Ready))
    } else {
      call.respond(HttpStatusCode.ServiceUnavailable, HealthResponse(HealthStatus.Starting))
    }
  }
}
