package com.linroid.ketch.server

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.torrent.TorrentCommandException
import com.linroid.ketch.core.file.PathRejectedException
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.server.api.ConnectionStreams
import com.linroid.ketch.server.api.PayloadTooLargeException
import com.linroid.ketch.server.api.ServerJson
import com.linroid.ketch.server.api.connectionRoutes
import com.linroid.ketch.server.api.downloadRoutes
import com.linroid.ketch.server.api.eventRoutes
import com.linroid.ketch.server.api.healthRoutes
import com.linroid.ketch.server.api.pairingRoutes
import com.linroid.ketch.server.api.serverRoutes
import com.linroid.ketch.server.api.toErrorResponse
import com.linroid.ketch.server.api.torrentErrorStatus
import com.linroid.ketch.server.api.torrentRoutes
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
import io.ktor.server.plugins.UnsupportedMediaTypeException
import io.ktor.server.plugins.contentnegotiation.ContentNegotiation
import io.ktor.server.plugins.cors.CORSConfig
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.origin
import io.ktor.server.plugins.statuspages.StatusPages
import io.ktor.server.request.acceptEncodingItems
import io.ktor.server.resources.Resources
import io.ktor.server.response.header
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
import java.net.InetAddress
import java.net.URL
import java.net.UnknownHostException
import java.util.concurrent.CountDownLatch
import java.util.zip.GZIPInputStream
import kotlin.coroutines.cancellation.CancellationException

/**
 * A daemon server that exposes a [KetchApi] instance via REST API and
 * real-time SSE events.
 *
 * Usage:
 * ```kotlin
 * val ketch = Ketch(httpEngine = KtorHttpEngine())
 * val server = KetchServer(ketch, host = "0.0.0.0", apiToken = token)
 * server.start()          // blocking
 * // or server.start(wait = false) for non-blocking
 * server.stop()
 * ```
 *
 * ## API Endpoints
 *
 * Paths are defined by the `Api` resources in `library:endpoints`.
 *
 * ### Server
 * - `GET  /api/status`             — server health and task counts
 * - `PUT  /api/config`             — update the download configuration, e.g. speed limit
 * - `GET  /api/network-interfaces` — discover interfaces and current selection
 * - `PUT  /api/network-interfaces` — select interfaces for new HTTP requests
 * - `POST /api/resolve`            — resolve URL metadata without downloading
 * - `POST /api/resolve/content`    — resolve metadata from uploaded file bytes (`?fileName=`)
 *
 * ### Tasks
 * - `GET    /api/tasks`                  — list all tasks
 * - `POST   /api/tasks`                  — create a new download
 * - `GET    /api/tasks/{id}`             — get task by ID
 * - `POST   /api/tasks/{id}/pause`       — pause a download
 * - `POST   /api/tasks/{id}/resume`      — resume a download (`?destination=`)
 * - `POST   /api/tasks/{id}/cancel`      — cancel a download
 * - `DELETE /api/tasks/{id}`             — remove a task (`?deleteFiles=true`)
 * - `PUT    /api/tasks/{id}/speed-limit` — set task speed limit
 * - `PUT    /api/tasks/{id}/priority`    — set task priority
 * - `PUT    /api/tasks/{id}/connections` — set task connections
 * - `PUT    /api/tasks/{id}/files`       — choose the files of a torrent task: `400`
 *   `invalid_selection`, `409` `selection_unavailable` (no file list yet, or canceled), `501`
 *   `unsupported` (not a task with files)
 *
 * ### Torrents
 *
 * Served from [KetchApi.torrents]; servers listing `torrent.control` have one. Failures answer
 * with the `TorrentCommandError` wire name: `400` `invalid_input`; `409` `invalid_state`,
 * `metadata_unavailable`, `revision_conflict` (with the current `revision`),
 * `idempotency_key_reused` and `stale_cursor`; `403` `policy_denied`; `429`
 * `resource_exhausted`; `404` `not_found`; `500` `storage_failure`; `501` `unsupported`.
 * - `GET /api/torrents/capabilities`   — the controller's capabilities (none without one)
 * - `GET /api/torrents/{id}`           — a torrent task's snapshot
 * - `GET /api/torrents/{id}/files`     — a page of its files
 *   (`?limit=1..1000&cursor=&sort=torrent|name|size|extension|selected&desc=true`)
 * - `PUT /api/torrents/{id}/selection` — choose its files, guarded by a revision
 * - `PUT /api/torrents/{id}/seeding`   — start or stop seeding it, guarded by a revision
 * - `GET /api/torrents/{id}/events`    — SSE: `snapshot` events, then `removed` or `error`
 *
 * ### Live connections
 *
 * Served from [KetchApi.activeConnections]; servers listing `net.activeConnections` report
 * them. Both take `?limit=1..1024` (default 256; `400` `invalid_limit` otherwise), and an engine
 * that does not report connections answers `501` `unsupported`. They carry hosts and peer
 * addresses, which `/api/events` never does.
 * - `GET /api/connections`        — a snapshot of the live connections
 * - `GET /api/connections/events` — SSE: a `snapshot` event right away, then one when the
 *   connections change and every 15 seconds while they do not; an `error` event ends it. At
 *   most 16 streams run at once; more are `429` `too_many_streams`
 *
 * ### Events (SSE)
 * - `GET /api/events`       — SSE stream of all task events
 * - `GET /api/events/{id}`  — SSE stream for a specific task
 *
 * ### Pairing
 * - `POST   /api/pairing`      — ask the owner for the access token
 * - `GET    /api/pairing/{id}` — whether the owner answered, with the token once allowed
 * - `DELETE /api/pairing/{id}` — withdraw the request
 *
 * ### Health
 * - `GET /api/health` — `200` once [ketch] serves its saved tasks, `503` before ([markReady])
 *
 * Until [markReady], every other API endpoint but pairing answers `503 Service Unavailable` with
 * a `starting` `ErrorResponse` and `Retry-After: 1`, so no download is added while the saved
 * tasks load.
 *
 * ## Access token
 *
 * With an [apiToken], every API request but the pairing and health endpoints must carry it as
 * `Authorization: Bearer <token>`. An address that sends ten wrong tokens within a minute gets
 * `429 Too Many Requests` for any token until that minute ends. Without one, anyone who can
 * reach [host] and [port] can use the
 * API, so the server binds to loopback by default and logs a warning when it listens on another
 * address without a token.
 *
 * ## Folders
 *
 * Callers choose where downloads are saved, and removing a task with `deleteFiles=true` deletes
 * its files. Without an [apiToken], and with one when [allowedDirectories] is not empty, the
 * server keeps them to the instance's download directory and [allowedDirectories]: a new task's
 * destination (relative paths are taken from the download directory, an existing file is never
 * overwritten), a resume's `destination`, the `defaultDirectory` of `PUT /api/config` and the
 * files `deleteFiles` removes. Anything else gets `403 Forbidden` with a `path_rejected`
 * `ErrorResponse`. Symbolic links count as the place they point to.
 *
 * ## Request bodies
 *
 * JSON bodies must be `application/json` (`415` otherwise) and are read up to 1 MiB, or 32 MiB
 * for a new task, which can carry a resolved torrent; uploaded content up to 16 MiB. Longer
 * bodies get `413 Payload Too Large`.
 *
 * ## Pairing
 *
 * With an [apiToken] and a [pairingApprover], a device can ask for the token instead of having
 * someone type it: the pairing endpoints take no token, [pairingApprover] decides, and the
 * device polls until it has the answer or the request expires after two minutes. One request
 * per address, and four in all, wait at a time. Web pages are refused, since with a token
 * [corsAllowedHosts] may let them call the API. The server advertises `pairing=1` over mDNS
 * when it takes requests.
 *
 * ## Host check
 *
 * Without an [apiToken], the server answers `403 Forbidden` with an `ErrorResponse` to any
 * request whose `Host` header, ignoring the port, is not one of:
 * - a loopback name or address: `localhost`, `*.localhost`, `127.0.0.0/8` or `[::1]`
 * - an IP address of one of this machine's network interfaces
 * - this machine's host name, or its mDNS name `<host>.local`
 * - an entry of [allowedHosts]
 *
 * This blocks DNS rebinding: a web page can re-resolve its own domain to this machine and
 * become same-origin with the API, which CORS cannot prevent, but its requests still carry
 * that domain as `Host`. Ketch apps connect to servers they discover over mDNS by IP
 * address, so they pass without configuration. Requests without a `Host` header pass too,
 * as browsers always send one.
 *
 * With an [apiToken] the check is off: a page cannot learn the token, so the token already
 * blocks the attack, and any name that reaches the server, such as a reverse proxy's or a
 * `nas.local` alias, keeps working.
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
 * Checking `Origin` does not stop DNS rebinding, where a page becomes same-origin with the
 * API; the host check does.
 *
 * @param ketch the KetchApi instance to expose
 * @param host bind address; loopback by default, so only this machine can connect
 * @param port listen port
 * @param apiToken bearer token every API request must carry, or `null` for no
 *   authentication, a `Host` header check, refusing web pages on other origins, and keeping
 *   callers to the download folders instead
 * @param name instance name advertised over mDNS
 * @param corsAllowedHosts origins whose web pages may call the API when there is an
 *   [apiToken]: `"*"` for any, a host such as `"localhost:3000"` for both `http` and
 *   `https`, or an origin such as `"http://localhost:3000"`. Ignored without a token.
 * @param allowedHosts extra `Host` names or IP addresses accepted when there is no
 *   [apiToken], for example a DNS name or a Docker host's address
 * @param allowedDirectories folders, besides the download directory, that callers may save
 *   downloads to and delete files from. Without an [apiToken] callers are always kept to the
 *   download directory and these folders; with one, only when this list is not empty.
 * @param mdnsEnabled whether to advertise the server over mDNS
 * @param mdnsRegistrar mDNS service registrar for LAN discovery; Android callers must supply
 *   an Android implementation, since the default registrar is compiled for the JVM
 * @param pairingApprover decides pairing requests, or `null` to take none; ignored without an
 *   [apiToken]
 * @param ready whether [ketch] already serves its saved tasks, as `GET /api/health` reports.
 *   Pass `false` to start listening before [KetchApi.start] returns, and call [markReady] then;
 *   until then the other API endpoints answer `503`.
 */
class KetchServer(
  private val ketch: KetchApi,
  private val host: String = "127.0.0.1",
  private val port: Int = 8642,
  private val apiToken: String? = null,
  private val name: String = "Ketch",
  private val corsAllowedHosts: List<String> = emptyList(),
  private val allowedHosts: List<String> = emptyList(),
  allowedDirectories: List<String> = emptyList(),
  private val mdnsEnabled: Boolean = true,
  private val mdnsRegistrar: MdnsRegistrar = defaultMdnsRegistrar(),
  pairingApprover: PairingApprover? = null,
  ready: Boolean = true,
) {
  private val log = KetchLogger("KetchServer")
  @Volatile
  private var ready = ready
  private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
  private val pairing = if (apiToken.isNullOrBlank() || pairingApprover == null) {
    null
  } else {
    PairingSessions(pairingApprover, apiToken, scope)
  }
  private val destinations = DestinationGuard(
    ketch,
    allowedDirectories,
    enabled = apiToken == null || allowedDirectories.isNotEmpty(),
  )
  private val throttle = AuthThrottle()
  private val connectionStreams = ConnectionStreams()
  private val stopped = CountDownLatch(1)
  private var engine: EmbeddedServer<CIOApplicationEngine, *> = embeddedServer(
    CIO,
    host = host,
    port = port,
    module = { configureServer() },
  )

  /**
   * Starts the daemon server. The server is listening when this returns or
   * starts waiting; a failure to bind, such as a port in use, is thrown.
   *
   * @param wait when `true` (default), blocks the calling thread
   *   until the server is stopped. Set to `false` for non-blocking.
   */
  fun start(wait: Boolean = true) {
    check(scope.isActive) { "Server has been stopped" }
    log.i { "Starting server on ${host}:${port}" }
    if (apiToken == null && !isLoopback(host)) {
      log.w {
        "Listening on $host:$port without an API token: anyone who can reach it can add" +
          " downloads, list, pause and remove tasks, and delete files in the download folders"
      }
    }
    engine.start(wait = false)
    startMdnsRegistration()
    if (wait) awaitStop()
  }

  /**
   * Tells health checks that [ketch] serves its saved tasks, so `GET /api/health` answers `200`
   * from now on; only needed when the server was created with `ready = false`.
   */
  fun markReady() {
    if (!ready) log.i { "Ready: saved tasks are restored" }
    ready = true
  }

  /** Blocks the calling thread until [stop] is called. */
  fun awaitStop() {
    stopped.await()
  }

  /**
   * Returns the port the server accepts connections on. With `port = 0` the system picks a free
   * port, which is known once the server has started.
   */
  suspend fun port(): Int = engine.engine.resolvedConnectors().first().port

  /** Stops the daemon server gracefully. */
  fun stop() {
    log.i { "Stopping server" }
    scope.cancel()
    try {
      engine.stop(
        gracePeriodMillis = 1000,
        timeoutMillis = 5000,
      )
    } finally {
      stopped.countDown()
    }
  }

  private fun startMdnsRegistration() {
    if (!mdnsEnabled) return
    scope.launch {
      val tokenValue =
        if (apiToken.isNullOrBlank()) "none"
        else "required"
      val metadata = buildMap {
        put("token", tokenValue)
        if (pairing != null) put("pairing", "1")
      }
      log.d {
        "Registering mDNS service:" +
          " name=$name," +
          " type=${MDNS_SERVICE_TYPE}," +
          " port=${port}," +
          " metadata=$metadata"
      }
      try {
        mdnsRegistrar.register(
          serviceType = MDNS_SERVICE_TYPE,
          serviceName = name,
          port = port,
          metadata = metadata,
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
      json(ServerJson)
    }

    install(Resources)

    install(SSE)

    if (apiToken == null) {
      // Nothing else stops web pages: refuse rebound domains, then pages on other origins.
      install(hostValidation(HostValidator(allowedHosts)))
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

    // Before authentication too: the API is closed to everyone while the tasks are restored.
    install(startupGate { ready })

    if (apiToken != null) {
      // Before authentication, so an address guessing tokens is turned away unchecked.
      install(authThrottling(throttle))
    }

    install(StatusPages) {
      exception<PathRejectedException> { call, cause ->
        log.w { "Refused path ${cause.path}: ${cause.message}" }
        call.respond(
          HttpStatusCode.Forbidden,
          ErrorResponse("path_rejected", cause.message ?: "Path is not allowed"),
        )
      }
      exception<PayloadTooLargeException> { call, cause ->
        call.respond(
          HttpStatusCode.PayloadTooLarge,
          ErrorResponse("payload_too_large", cause.message ?: "Request body is too large"),
        )
      }
      exception<UnsupportedMediaTypeException> { call, cause ->
        call.respond(
          HttpStatusCode.UnsupportedMediaType,
          ErrorResponse(
            "unsupported_media_type",
            cause.message ?: "Request body must be application/json",
          ),
        )
      }
      exception<TorrentCommandException> { call, cause ->
        call.respond(torrentErrorStatus(cause.error), cause.toErrorResponse())
      }
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
      val tokenCheck = TokenCheck(expectedToken)
      install(Authentication) {
        bearer(AUTH_API) {
          authenticate { credential ->
            if (tokenCheck.matches(credential.token)) {
              UserIdPrincipal("api")
            } else {
              val address = request.origin.remoteAddress
              log.w { "Rejected a wrong API token from $address" }
              throttle.recordFailure(address)
              null
            }
          }
        }
      }
    }

    routing {
      if (apiToken != null) {
        authenticate(AUTH_API) {
          apiRoutes(ketch, destinations, connectionStreams)
        }
      } else {
        apiRoutes(ketch, destinations, connectionStreams)
      }
      pairing?.let { pairingRoutes(it) }
      healthRoutes { ready }
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

private fun Route.apiRoutes(
  ketch: KetchApi,
  destinations: DestinationGuard,
  connectionStreams: ConnectionStreams,
) {
  serverRoutes(ketch, destinations)
  downloadRoutes(ketch, destinations)
  torrentRoutes(ketch)
  connectionRoutes(ketch, connectionStreams)
  eventRoutes(ketch)
}

/** Whether [host], a bind address, only accepts connections from this machine. */
private fun isLoopback(host: String): Boolean {
  if (host.equals("localhost", ignoreCase = true)) return true
  return try {
    InetAddress.getByName(host).isLoopbackAddress
  } catch (_: UnknownHostException) {
    false
  }
}

/**
 * Serves bundled web UI resources from the classpath.
 * Uses [ClassLoader.getResource] directly for GraalVM native image
 * compatibility, since Ktor's `staticResources` relies on classpath
 * scanning that doesn't work in native images.
 *
 * A file bundled only as `<name>.gz` (the CLI gzips its largest ones) is sent as it is to clients
 * that accept gzip and unpacked for the others.
 */
internal fun Route.webResources(
  resource: (String) -> URL? = KetchServer::class.java.classLoader::getResource,
) {
  get("{path...}") {
    val path = call.parameters.getAll("path")
      ?.joinToString("/")
      ?.ifEmpty { "index.html" }
      ?: "index.html"
    val ext = path.substringAfterLast('.', "")
    val contentType = ContentType.defaultForFileExtension(ext)
    val plain = resource("web/$path")
    if (plain != null) {
      call.respondBytes(plain.readBytes(), contentType)
      return@get
    }
    val gzipped = resource("web/$path.gz") ?: return@get
    call.response.header(HttpHeaders.Vary, HttpHeaders.AcceptEncoding)
    // An explicit gzip entry decides, so "gzip;q=0, *" refuses gzip; "*" counts only without one.
    val encodings = call.request.acceptEncodingItems()
    val gzip = encodings.find { it.value.equals("gzip", ignoreCase = true) }
      ?: encodings.find { it.value == "*" }
    val acceptsGzip = gzip != null && gzip.quality > 0.0
    if (acceptsGzip) {
      call.response.header(HttpHeaders.ContentEncoding, "gzip")
      call.respondBytes(gzipped.readBytes(), contentType)
    } else {
      call.respondBytes(GZIPInputStream(gzipped.openStream()).use { it.readBytes() }, contentType)
    }
  }
}
