package com.linroid.kdown.server

import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.endpoints.model.ErrorResponse
import com.linroid.kdown.server.api.downloadRoutes
import com.linroid.kdown.server.api.eventRoutes
import com.linroid.kdown.server.api.serverRoutes
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.application.call
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.header
import io.ktor.server.resources.Resources
import io.ktor.server.response.respond
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.serialization.json.Json

/**
 * A daemon server that exposes a [KDownApi] instance via REST API and
 * real-time SSE events.
 *
 * Usage:
 * ```kotlin
 * val kdown = KDown(httpEngine = KtorHttpEngine())
 * val server = KDownServer(kdown)
 * server.start()          // blocking
 * // or server.start(wait = false) for non-blocking
 * server.stop()
 * ```
 *
 * ## API Endpoints
 *
 * ### Tasks
 * - `GET    /api/tasks`            — list all tasks
 * - `POST   /api/tasks`            — create a new download
 * - `GET    /api/tasks/{id}`       — get task by ID
 * - `POST   /api/tasks/{id}/pause` — pause a download
 * - `POST   /api/tasks/{id}/resume`— resume a download
 * - `POST   /api/tasks/{id}/cancel`— cancel a download
 * - `DELETE /api/tasks/{id}`       — remove a task
 * - `PUT    /api/tasks/{id}/speed-limit` — set task speed limit
 * - `PUT    /api/tasks/{id}/priority`    — set task priority
 *
 * ### Server
 * - `GET /api/status`       — server health and task counts
 * - `PUT /api/speed-limit`  — set global speed limit
 *
 * ### Events
 * - `GET /api/events`       — SSE stream of all task events
 * - `GET /api/events/{id}`  — SSE stream for a specific task
 *
 * @param kdown the KDownApi instance to expose
 * @param config server configuration (host, port, auth, CORS)
 */
class KDownServer(
  private val kdown: KDownApi,
  private val config: KDownServerConfig = KDownServerConfig.Default,
) {
  private var engine:
    EmbeddedServer<NettyApplicationEngine, *>? = null

  /**
   * Starts the daemon server.
   *
   * @param wait when `true` (default), blocks the calling thread
   *   until the server is stopped. Set to `false` for non-blocking.
   */
  fun start(wait: Boolean = true) {
    engine = embeddedServer(
      Netty,
      host = config.host,
      port = config.port,
      module = { configureServer() },
    ).also { it.start(wait = wait) }
  }

  /** Stops the daemon server gracefully. */
  fun stop() {
    engine?.stop(
      gracePeriodMillis = 1000,
      timeoutMillis = 5000,
    )
    engine = null
  }

  internal fun Application.configureServer() {
    install(ContentNegotiation) {
      json(Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
      })
    }

    install(Resources)

    install(SSE)

    if (config.corsAllowedHosts.isNotEmpty()) {
      install(CORS) {
        if ("*" in config.corsAllowedHosts) {
          anyHost()
        } else {
          config.corsAllowedHosts.forEach { host ->
            allowHost(host)
          }
        }
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Put)
        allowMethod(HttpMethod.Delete)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
      }
    }

    install(StatusPages) {
      exception<IllegalArgumentException> { call, cause ->
        call.respond(
          HttpStatusCode.BadRequest,
          ErrorResponse(
            "bad_request",
            cause.message ?: "Bad request"
          )
        )
      }
      exception<Throwable> { call, cause ->
        call.respond(
          HttpStatusCode.InternalServerError,
          ErrorResponse(
            "internal_error",
            cause.message ?: "Internal server error"
          )
        )
      }
    }

    if (config.apiToken != null) {
      intercept(ApplicationCallPipeline.Call) {
        val token = call.request.header(
          HttpHeaders.Authorization
        )
        if (token != "Bearer ${config.apiToken}") {
          call.respond(
            HttpStatusCode.Unauthorized,
            ErrorResponse(
              "unauthorized",
              "Invalid or missing authorization token"
            )
          )
          finish()
        }
      }
    }

    routing {
      serverRoutes(kdown)
      downloadRoutes(kdown)
      eventRoutes(kdown)
    }
  }
}
