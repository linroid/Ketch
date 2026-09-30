package com.linroid.ketch.server

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.server.api.downloadRoutes
import com.linroid.ketch.server.api.eventRoutes
import com.linroid.ketch.server.api.serverRoutes
import com.linroid.ketch.server.mdns.MdnsRegistrar
import com.linroid.ketch.server.mdns.defaultMdnsRegistrar
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
import io.ktor.server.plugins.cors.CORSConfig
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
 * A daemon server that exposes a [KetchApi] instance via REST API and
 * real-time SSE events.
 *
 * Usage:
 * ```kotlin
 * val ketch = Ketch(httpEngine = KtorHttpEngine())
 * val server = KetchServer(ketch)
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
 * ## Browsers
 *
 * Without an [apiToken], the server answers `403 Forbidden` with an `origin_not_allowed`
 * `ErrorResponse` to any request from a web page on another origin, including CORS
 * preflights, and ignores [corsAllowedHosts]. Otherwise any site the user visits could
 * create downloads that write anywhere the user can, list tasks, and pause or cancel them.
 * A web page's request is one whose `Origin` is `http`, `https` or `null`; it is from another
 * origin when that origin's host and port differ from the `Host` header. These pass:
 * - clients outside a browser, such as the apps, `RemoteKetch` and `curl`, which send no
 *   `Origin`
 * - the web UI this server serves, whose pages share its origin
 * - browser extensions, such as Ketch's own: a browser only lets them reach the server with
 *   the host permission the user granted
 *
 * With an [apiToken], the server does not check `Origin`, since a page cannot learn the
 * token, and [corsAllowedHosts] lists the origins whose pages may call the API. Browser
 * extensions and other non-web origins are always allowed; they need the token like any
 * client.
 *
 * Checking `Origin` does not stop DNS rebinding, where a page re-resolves its own domain to
 * this machine and becomes same-origin with the API; its requests still name that domain in
 * the `Host` header.
 *
 * @param ketch the KetchApi instance to expose
 * @param apiToken bearer token every API request must carry, or `null` for no
 *   authentication, in which case web pages on other origins are refused
 * @param corsAllowedHosts origins whose web pages may call the API when there is an
 *   [apiToken]: `"*"` for any, a host such as `"localhost:3000"` for both `http` and
 *   `https`, or an origin such as `"http://localhost:3000"`. Ignored without a token.
 * @param mdnsRegistrar mDNS service registrar for LAN discovery
 */
class KetchServer(
  private val ketch: KetchApi,
  private val host: String = "0.0.0.0",
  private val port: Int = 8642,
  private val apiToken: String? = null,
  private val name: String = "Ketch",
  private val corsAllowedHosts: List<String> = emptyList(),
  private val mdnsEnabled: Boolean = true,
  private val mdnsRegistrar: MdnsRegistrar = defaultMdnsRegistrar(),
) {
  private val log = KetchLogger("KetchServer")
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private var engine: EmbeddedServer<CIOApplicationEngine, *> = embeddedServer(
    CIO,
    host = host,
    port = port,
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
    log.i { "Starting server on ${host}:${port}" }
    engine.start(wait = wait)
    startMdnsRegistration()
  }

  /** Stops the daemon server gracefully. */
  fun stop() {
    log.i { "Stopping server" }
    scope.cancel()
    engine.stop(
      gracePeriodMillis = 1000,
      timeoutMillis = 5000,
    )
  }

  private fun startMdnsRegistration() {
    if (!mdnsEnabled) return
    scope.launch {
      val tokenValue =
        if (apiToken.isNullOrBlank()) "none"
        else "required"
      log.d {
        "Registering mDNS service:" +
          " name=$name," +
          " type=${MDNS_SERVICE_TYPE}," +
          " port=${port}," +
          " token=$tokenValue"
      }
      try {
        mdnsRegistrar.register(
          serviceType = MDNS_SERVICE_TYPE,
          serviceName = name,
          port = port,
          metadata = mapOf("token" to tokenValue),
        )
        log.i { "mDNS registered: $name (${MDNS_SERVICE_TYPE})" }
        awaitCancellation()
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w(e) { "mDNS registration failed: ${e.message}" }
      } finally {
        runCatching {
          mdnsRegistrar.unregister()
        }.onFailure { e ->
          log.w(e) { "mDNS unregister failed: ${e.message}" }
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

    if (apiToken == null) {
      install(CrossOriginGuard)
      if (corsAllowedHosts.isNotEmpty()) {
        log.w { "Ignoring corsAllowedHosts $corsAllowedHosts: they need an API token" }
      }
    } else if (corsAllowedHosts.isNotEmpty()) {
      install(CORS) {
        corsAllowedHosts.forEach { allowCorsEntry(it) }
        // Extensions reach the server through their host permission, not CORS; this only
        // keeps the list from refusing them.
        allowOrigins { !isWebOrigin(it) }
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
      exception<io.ktor.server.plugins.BadRequestException> { call, cause ->
        call.respond(
          HttpStatusCode.BadRequest,
          ErrorResponse(
            "bad_request", cause.message ?: "Bad request",
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

    apiToken?.let { expectedToken ->
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
      if (apiToken != null) {
        authenticate(AUTH_API) {
          apiRoutes(ketch)
        }
      } else {
        apiRoutes(ketch)
      }
      webResources()
    }
  }

  companion object {
    private const val AUTH_API = "api-bearer"

    /** DNS-SD service type for Ketch server discovery. */
    internal const val MDNS_SERVICE_TYPE = "_ketch._tcp"
  }
}

/**
 * Allows [entry] of `corsAllowedHosts`: `*`, a host for both `http` and `https`, or an origin
 * with its scheme, which [CORSConfig.allowHost] only takes as a separate argument.
 */
private fun CORSConfig.allowCorsEntry(entry: String) {
  val scheme = entry.substringBefore("://", "")
  if (scheme.isEmpty()) {
    allowHost(entry)
  } else {
    allowHost(entry.substringAfter("://").removeSuffix("/"), schemes = listOf(scheme))
  }
}

private fun Route.apiRoutes(ketch: KetchApi) {
  serverRoutes(ketch)
  downloadRoutes(ketch)
  eventRoutes(ketch)
}

/**
 * Serves bundled web UI resources from the classpath.
 * Uses [ClassLoader.getResource] directly for GraalVM native image
 * compatibility, since Ktor's `staticResources` relies on classpath
 * scanning that doesn't work in native images.
 */
private fun Route.webResources() {
  val loader = KetchServer::class.java.classLoader
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
