package com.linroid.kdown.server

import com.linroid.kdown.api.KDownApi
import com.linroid.kdown.core.log.KDownLogger
import com.linroid.kdown.endpoints.model.ErrorResponse
import com.linroid.kdown.server.api.downloadRoutes
import com.linroid.kdown.server.api.eventRoutes
import com.linroid.kdown.api.config.ServerConfig
import com.linroid.kdown.server.api.serverRoutes
import com.linroid.kdown.server.mdns.MdnsRegistrar
import com.linroid.kdown.server.mdns.defaultMdnsRegistrar
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.defaultForFileExtension
import io.ktor.serialization.kotlinx.json.json
import io.ktor.server.application.Application
import io.ktor.server.application.install
import io.ktor.server.auth.Authentication
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.authenticate
import io.ktor.server.auth.bearer
import io.ktor.server.cio.CIO
import io.ktor.server.cio.CIOApplicationEngine
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.resources.Resources
import io.ktor.server.response.respond
import io.ktor.server.response.respondBytes
import io.ktor.server.routing.Route
import io.ktor.server.routing.get
import io.ktor.server.routing.routing
import io.ktor.server.sse.SSE
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.serialization.json.Json
import kotlin.coroutines.cancellation.CancellationException

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
 * - `GET  /api/status`       — server health and task counts
 * - `PUT  /api/speed-limit`  — set global speed limit
 * - `POST /api/resolve`      — resolve URL metadata without downloading
 *
 * ### Events
 * - `GET /api/events`       — SSE stream of all task events
 * - `GET /api/events/{id}`  — SSE stream for a specific task
 *
 * @param kdown the KDownApi instance to expose
 * @param config server configuration (host, port, auth, CORS)
 * @param mdnsRegistrar mDNS service registrar for LAN discovery
 */
class KDownServer(
  private val kdown: KDownApi,
  private val config: ServerConfig = ServerConfig(),
  private val mdnsServiceName: String = "KDown",
  private val mdnsRegistrar: MdnsRegistrar = defaultMdnsRegistrar(),
) {
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private var engine: EmbeddedServer<CIOApplicationEngine, *> = embeddedServer(
    CIO,
    host = config.host,
    port = config.port,
    module = { configureServer() },
  )

  /**
   * Starts the daemon server.
   *
   * @param wait when `true` (default), blocks the calling thread
   *   until the server is stopped. Set to `false` for non-blocking.
   */
  fun start(wait: Boolean = true) {
    check(scope.isActive) { "Server has been stopped" }
    engine.start(wait = wait)
    startMdnsRegistration()
  }

  /** Stops the daemon server gracefully. */
  fun stop() {
    scope.cancel()
    engine.stop(
      gracePeriodMillis = 1000,
      timeoutMillis = 5000,
    )
  }

  private fun startMdnsRegistration() {
    if (!config.mdnsEnabled) return
    scope.launch {
      val tokenValue =
        if (config.apiToken.isNullOrBlank()) "none"
        else "required"
      KDownLogger.d(TAG) {
        "Registering mDNS service:" +
          " name=$mdnsServiceName," +
          " type=${ServerConfig.MDNS_SERVICE_TYPE}," +
          " port=${config.port}," +
          " token=$tokenValue"
      }
      try {
        mdnsRegistrar.register(
          serviceType = ServerConfig.MDNS_SERVICE_TYPE,
          serviceName = mdnsServiceName,
          port = config.port,
          metadata = mapOf("token" to tokenValue),
        )
        KDownLogger.i(TAG) {
          "mDNS registered: $mdnsServiceName" +
            " (${ServerConfig.MDNS_SERVICE_TYPE})"
        }
        awaitCancellation()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        KDownLogger.w(TAG, throwable = e) {
          "mDNS registration failed: ${e.message}"
        }
      } finally {
        runCatching {
          mdnsRegistrar.unregister()
        }.onFailure { e ->
          KDownLogger.w(TAG, throwable = e) {
            "mDNS unregister failed: ${e.message}"
          }
        }
      }
    }
  }

  internal fun Application.configureServer() {
    install(ContentNegotiation) {
      json(
        Json {
          encodeDefaults = true
          ignoreUnknownKeys = true
        },
      )
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
            cause.message ?: "Bad request",
          ),
        )
      }
      exception<Throwable> { call, cause ->
        call.respond(
          HttpStatusCode.InternalServerError,
          ErrorResponse(
            "internal_error",
            cause.message ?: "Internal server error",
          ),
        )
      }
    }

    config.apiToken?.let { expectedToken ->
      install(Authentication) {
        bearer(AUTH_API) {
          authenticate { credential ->
            if (credential.token == expectedToken) {
              UserIdPrincipal("api")
            } else {
              null
            }
          }
        }
      }
    }

    routing {
      if (config.apiToken != null) {
        authenticate(AUTH_API) {
          apiRoutes(kdown)
        }
      } else {
        apiRoutes(kdown)
      }
      webResources()
    }
  }

  companion object {
    private const val TAG = "KDownServer"
    private const val AUTH_API = "api-bearer"
  }
}

private fun Route.apiRoutes(kdown: KDownApi) {
  serverRoutes(kdown)
  downloadRoutes(kdown)
  eventRoutes(kdown)
}

/**
 * Serves bundled web UI resources from the classpath.
 * Uses [ClassLoader.getResource] directly for GraalVM native image
 * compatibility, since Ktor's `staticResources` relies on classpath
 * scanning that doesn't work in native images.
 */
private fun Route.webResources() {
  val loader = KDownServer::class.java.classLoader
  get("{path...}") {
    val path = call.parameters.getAll("path")
      ?.joinToString("/")
      ?.ifEmpty { "index.html" }
      ?: "index.html"
    val resource = loader.getResource("web/$path")
    if (resource != null) {
      val ext = path.substringAfterLast('.', "")
      val contentType =
        ContentType.defaultForFileExtension(ext)
      call.respondBytes(
        resource.readBytes(),
        contentType,
      )
    }
  }
}

