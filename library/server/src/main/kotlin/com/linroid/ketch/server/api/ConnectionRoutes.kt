package com.linroid.ketch.server.api

import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.ConnectionEvents
import com.linroid.ketch.endpoints.model.ErrorResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.request.httpVersion
import io.ktor.server.resources.get
import io.ktor.server.response.header
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.sse.SSEServerContent
import io.ktor.server.sse.ServerSSESession
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.transformLatest
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KetchLogger("ConnectionRoutes")

/** How many clients a server streams live connections to at once. */
internal const val MAX_CONNECTION_STREAMS = 16

/** How often a stream repeats its last snapshot while the connections do not change. */
internal val CONNECTION_KEEPALIVE: Duration = 15.seconds

/**
 * Counts the open live connection streams of a server, so at most [max] run at once: each
 * serializes its own snapshots once a second.
 */
internal class ConnectionStreams(
  val max: Int = MAX_CONNECTION_STREAMS,
  val keepAlive: Duration = CONNECTION_KEEPALIVE,
) {
  private val open = AtomicInteger()

  /** Whether another stream would go past [max]. */
  val isFull: Boolean get() = open.get() >= max

  /** Takes a slot for a stream, or returns `false` when all [max] are taken. */
  fun tryOpen(): Boolean {
    while (true) {
      val current = open.get()
      if (current >= max) return false
      if (open.compareAndSet(current, current + 1)) return true
    }
  }

  /** Gives back the slot of a stream that [tryOpen] let in. */
  fun close() {
    open.decrementAndGet()
  }
}

/**
 * Installs `GET /api/connections` and its SSE stream `GET /api/connections/events` over
 * [KetchApi.activeConnections]. Both take a `limit` in 1..[ActiveConnections.MAX_LIMIT] (`400`
 * `invalid_limit` otherwise); an engine that does not report connections answers `501`
 * `unsupported`, or sends that `error` event. They carry peer addresses, so they are only served
 * here, behind the access token, and never on `/api/events`.
 */
internal fun Route.connectionRoutes(ketch: KetchApi, streams: ConnectionStreams) {
  get<Api.ActiveConnections> { resource ->
    if (!call.checkLimit(resource.limit)) return@get
    val snapshot = try {
      ketch.activeConnections(resource.limit).first()
    } catch (e: UnsupportedOperationException) {
      call.respond(HttpStatusCode.NotImplemented, unsupported(e))
      return@get
    }
    call.respond(snapshot)
  }

  get<Api.ActiveConnections.Events> { resource ->
    val limit = resource.parent.limit
    if (!call.checkLimit(limit)) return@get
    // Turned away before the stream starts, while a status can still say so; a stream that
    // loses the race for the last slot sends the same error as an event.
    if (streams.isFull) {
      log.w { "Refused a connections stream: ${streams.max} are open" }
      call.respond(HttpStatusCode.TooManyRequests, tooManyStreams(streams.max))
      return@get
    }
    log.d { "SSE client connected to /api/connections/events limit=$limit" }
    call.respondEvents { streamConnections(ketch, limit, streams) }
  }
}

/** Answers `400` `invalid_limit` and returns `false` unless [limit] is in range. */
private suspend fun ApplicationCall.checkLimit(limit: Int): Boolean {
  if (limit in 1..ActiveConnections.MAX_LIMIT) return true
  respond(
    HttpStatusCode.BadRequest,
    ErrorResponse(
      ConnectionEvents.INVALID_LIMIT,
      "The limit must be between 1 and ${ActiveConnections.MAX_LIMIT}",
    ),
  )
  return false
}

/** Responds with an SSE stream run by [handler], as Ktor's `sse` routes do. */
private suspend fun ApplicationCall.respondEvents(handler: suspend ServerSSESession.() -> Unit) {
  response.header(HttpHeaders.ContentType, ContentType.Text.EventStream.toString())
  response.header(HttpHeaders.CacheControl, "no-store")
  if (request.httpVersion.startsWith("HTTP/1.")) {
    response.header(HttpHeaders.Connection, "keep-alive")
  }
  response.header("X-Accel-Buffering", "no")
  respond(SSEServerContent(this, handler))
}

private suspend fun ServerSSESession.streamConnections(
  ketch: KetchApi,
  limit: Int,
  streams: ConnectionStreams,
) {
  if (!streams.tryOpen()) {
    sendError(tooManyStreams(streams.max))
    return
  }
  try {
    var sequence = 0L
    var failure: Throwable? = null
    ketch.activeConnections(limit)
      .repeatWhileIdle(streams.keepAlive)
      // Only the engine's failures; a failed send means the client left, and ends the stream.
      .catch { failure = it }
      .collect { snapshot ->
        sequence++
        send(
          ServerSentEvent(
            data = ServerJson.encodeToString(snapshot),
            event = ConnectionEvents.SNAPSHOT,
            id = sequence.toString(),
          ),
        )
      }
    when (val error = failure) {
      null -> {}
      is CancellationException -> throw error
      is UnsupportedOperationException -> sendError(unsupported(error))
      else -> {
        log.w { "Connections stream failed: ${error.describeCauses()}" }
        sendError(ErrorResponse("internal_error", "Live connections are unavailable"))
      }
    }
  } finally {
    streams.close()
  }
}

/**
 * Repeats the last snapshot every [period] while no new one comes, so proxies and clients see a
 * stream that is still alive: the engine only emits when something changes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
private fun Flow<ActiveConnections>.repeatWhileIdle(period: Duration): Flow<ActiveConnections> =
  transformLatest { snapshot ->
    emit(snapshot)
    while (true) {
      delay(period)
      emit(snapshot)
    }
  }

private suspend fun ServerSSESession.sendError(error: ErrorResponse) {
  send(ServerSentEvent(data = ServerJson.encodeToString(error), event = ConnectionEvents.ERROR))
}

private fun unsupported(e: UnsupportedOperationException) = ErrorResponse(
  ConnectionEvents.UNSUPPORTED,
  e.message ?: "This server does not report its connections",
)

private fun tooManyStreams(max: Int) = ErrorResponse(
  ConnectionEvents.TOO_MANY_STREAMS,
  "This server already streams its connections to $max clients",
)
