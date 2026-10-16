package com.linroid.ketch.server

import com.linroid.ketch.endpoints.model.ErrorResponse
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationPlugin
import io.ktor.server.application.createApplicationPlugin
import io.ktor.server.request.path
import io.ktor.server.response.header
import io.ktor.server.response.respond

/**
 * Answers API requests with `503 Service Unavailable` and a `starting` [ErrorResponse] until
 * [isReady]: a download added while the saved tasks load could race the restore, which replaces
 * the task list. The health and pairing endpoints, which touch no task, and the web UI stay open.
 * Clients retry: `Retry-After` asks for a second, and `RemoteKetch` reconnects.
 */
internal fun startupGate(isReady: () -> Boolean): ApplicationPlugin<Unit> =
  createApplicationPlugin("StartupGate") {
    onCall { call ->
      if (isReady()) return@onCall
      val path = call.request.path()
      val gated = path.startsWith("/api/") && path != "/api/health" &&
        path != "/api/pairing" && !path.startsWith("/api/pairing/")
      if (!gated) return@onCall
      call.response.header(HttpHeaders.RetryAfter, "1")
      call.respond(
        HttpStatusCode.ServiceUnavailable,
        ErrorResponse("starting", "Ketch is still restoring its downloads; try again shortly"),
      )
    }
  }
