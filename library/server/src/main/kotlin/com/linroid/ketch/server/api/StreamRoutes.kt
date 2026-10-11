package com.linroid.ketch.server.api

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamSubscription
import com.linroid.ketch.server.stream.EventHub
import com.linroid.ketch.server.stream.UnknownStreamException
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.ApplicationCall
import io.ktor.server.auth.UserIdPrincipal
import io.ktor.server.auth.principal
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.sse.heartbeat
import io.ktor.sse.ServerSentEvent
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

private val log = KetchLogger("StreamRoutes")

/** How often an idle stream sends a comment, so proxies and clients see it is alive. */
internal val STREAM_HEARTBEAT: Duration = 15.seconds

/**
 * Installs the subscribed event stream, `GET /api/stream`, and its subscription,
 * `PUT /api/stream/{id}/subscription`, over [hub]. Beyond [EventHub.maxStreams] open streams,
 * `GET` answers `429` `too_many_streams`.
 */
internal fun Route.streamRoutes(hub: EventHub, heartbeat: Duration = STREAM_HEARTBEAT) {
  get<Api.Stream> {
    val stream = hub.open(call.owner())
    if (stream == null) {
      log.w { "Refused an event stream: ${hub.maxStreams} are open" }
      call.respond(
        HttpStatusCode.TooManyRequests,
        ErrorResponse(
          StreamEvents.TOO_MANY_STREAMS,
          "This server already streams events to ${hub.maxStreams} clients",
        ),
      )
      return@get
    }
    log.d { "SSE client connected to /api/stream" }
    var started = false
    try {
      call.respondEvents {
        started = true
        heartbeat {
          period = heartbeat
          event = ServerSentEvent(comments = "heartbeat")
        }
        stream.run { send(ServerSentEvent(data = it.data, event = it.event, id = it.id)) }
      }
    } finally {
      // A response that never started its handler leaves the stream to close here.
      if (!started) stream.close()
    }
  }

  put<Api.Stream.ById.Subscription> { resource ->
    val subscription = call.receiveJson<StreamSubscription>()
    val applied = try {
      hub.subscribe(resource.parent.id, call.owner(), subscription)
    } catch (_: UnknownStreamException) {
      call.respond(
        HttpStatusCode.NotFound,
        ErrorResponse(StreamEvents.UNKNOWN_STREAM, "No such event stream"),
      )
      return@put
    } catch (e: IllegalArgumentException) {
      call.respond(
        HttpStatusCode.BadRequest,
        ErrorResponse(StreamEvents.INVALID_SUBSCRIPTION, e.message ?: "Invalid subscription"),
      )
      return@put
    }
    log.d { "Event stream subscription revision=${applied.revision}" }
    call.respond(applied)
  }
}

/** Who opened a stream: the credentials' principal, or `null` on a server without a token. */
private fun ApplicationCall.owner(): String? = principal<UserIdPrincipal>()?.name
