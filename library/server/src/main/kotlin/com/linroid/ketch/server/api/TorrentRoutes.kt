package com.linroid.ketch.server.api

import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.torrent.TorrentCapabilities
import com.linroid.ketch.api.torrent.TorrentCommandError
import com.linroid.ketch.api.torrent.TorrentCommandException
import com.linroid.ketch.api.torrent.TorrentController
import com.linroid.ketch.api.torrent.TorrentFileOrder
import com.linroid.ketch.api.torrent.TorrentPageRequest
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.TorrentSeedingRequest
import com.linroid.ketch.endpoints.model.TorrentSelectionRequest
import io.ktor.http.HttpStatusCode
import io.ktor.server.resources.get
import io.ktor.server.resources.put
import io.ktor.server.response.respond
import io.ktor.server.routing.Route
import io.ktor.server.sse.ServerSSESession
import io.ktor.server.sse.sse
import io.ktor.sse.ServerSentEvent
import kotlinx.coroutines.flow.transformWhile
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

private val log = KetchLogger("TorrentRoutes")

/** Upper bound of the `limit` of a file page. */
private const val MAX_PAGE_LIMIT = 1000

/** Upper bound of a file page cursor's length. */
private const val MAX_CURSOR_LENGTH = 4096

/**
 * Installs the `/api/torrents` routes over [KetchApi.torrents]. Command failures throw
 * [TorrentCommandException], which the server's status pages answer with the error's wire name
 * ([torrentErrorStatus]).
 */
internal fun Route.torrentRoutes(ketch: KetchApi) {
  get<Api.Torrents.Capabilities> {
    call.respond(ketch.torrents?.capabilities() ?: TorrentCapabilities())
  }

  get<Api.Torrents.ById> { resource ->
    log.d { "GET /api/torrents/${resource.id}" }
    val snapshot = controller(ketch).snapshot(resource.id) ?: throw notFound(resource.id)
    call.respond(snapshot)
  }

  get<Api.Torrents.ById.Files> { resource ->
    val taskId = resource.parent.id
    log.d { "GET /api/torrents/$taskId/files" }
    val limit = resource.limit ?: TorrentPageRequest().limit
    if (limit !in 1..MAX_PAGE_LIMIT) {
      throw invalidInput("The page limit must be between 1 and $MAX_PAGE_LIMIT")
    }
    val cursor = resource.cursor
    if (cursor != null && (cursor.isBlank() || cursor.length > MAX_CURSOR_LENGTH)) {
      throw invalidInput("Invalid page cursor")
    }
    val order = resource.sort?.let {
      TorrentFileOrder.fromWire(it) ?: throw invalidInput(
        "Unknown sort order; use one of " + TorrentFileOrder.entries.joinToString { o ->
          o.wireName
        }
      )
    } ?: TorrentFileOrder.TORRENT
    val page = controller(ketch).files(
      taskId = taskId,
      page = TorrentPageRequest(limit, cursor),
      order = order,
      descending = resource.desc ?: false,
    ) ?: throw notFound(taskId)
    call.respond(page)
  }

  put<Api.Torrents.ById.Selection> { resource ->
    val taskId = resource.parent.id
    log.d { "PUT /api/torrents/$taskId/selection" }
    val body = call.receiveJson<TorrentSelectionRequest>()
    call.respond(controller(ketch).select(taskId, body.fileIds, body.context))
  }

  put<Api.Torrents.ById.Seeding> { resource ->
    val taskId = resource.parent.id
    val body = call.receiveJson<TorrentSeedingRequest>()
    log.d { "PUT /api/torrents/$taskId/seeding seeding=${body.seeding}" }
    call.respond(controller(ketch).setSeeding(taskId, body.seeding, body.context))
  }

  sse("/api/torrents/{id}/events") {
    val taskId = call.parameters["id"]!!
    log.d { "SSE client connected to /api/torrents/$taskId/events" }
    val controller = ketch.torrents
    if (controller == null) {
      sendError(unsupported())
      return@sse
    }
    try {
      controller.observe(taskId)
        .transformWhile { snapshot ->
          emit(snapshot)
          snapshot != null
        }
        .collect { snapshot ->
          if (snapshot == null) {
            send(
              ServerSentEvent(
                data = buildJsonObject { put("taskId", taskId) }.toString(),
                event = EVENT_REMOVED,
              ),
            )
          } else {
            send(
              ServerSentEvent(
                data = ServerJson.encodeToString(snapshot),
                event = EVENT_SNAPSHOT,
                id = "${snapshot.revision.epoch}:${snapshot.revision.sequence}",
              ),
            )
          }
        }
    } catch (e: TorrentCommandException) {
      log.d { "Torrent events of taskId=$taskId ended: ${e.error.wireName}" }
      sendError(e)
    }
  }
}

/** SSE event carrying a `TorrentSnapshot`. */
internal const val EVENT_SNAPSHOT = "snapshot"

/** SSE event telling that the task is gone; the stream closes after it. */
internal const val EVENT_REMOVED = "removed"

/** SSE event carrying an `ErrorResponse`; the stream closes after it. */
internal const val EVENT_ERROR = "error"

private suspend fun ServerSSESession.sendError(e: TorrentCommandException) {
  send(
    ServerSentEvent(
      data = ServerJson.encodeToString(e.toErrorResponse()),
      event = EVENT_ERROR,
    ),
  )
}

private fun controller(ketch: KetchApi): TorrentController = ketch.torrents ?: throw unsupported()

private fun unsupported() = TorrentCommandException(
  TorrentCommandError.UNSUPPORTED,
  "This server has no torrent controls",
)

private fun notFound(taskId: String) = TorrentCommandException(
  TorrentCommandError.NOT_FOUND,
  "No torrent download $taskId",
)

private fun invalidInput(message: String) =
  TorrentCommandException(TorrentCommandError.INVALID_INPUT, message)

/** The error response of a torrent command failure. */
internal fun TorrentCommandException.toErrorResponse() = ErrorResponse(
  error = error.wireName,
  message = message ?: error.wireName,
  revision = currentRevision,
)

/** The HTTP status a torrent command failure answers with. */
internal fun torrentErrorStatus(error: TorrentCommandError): HttpStatusCode = when (error) {
  TorrentCommandError.UNSUPPORTED -> HttpStatusCode.NotImplemented
  TorrentCommandError.INVALID_INPUT -> HttpStatusCode.BadRequest
  TorrentCommandError.INVALID_STATE,
  TorrentCommandError.METADATA_UNAVAILABLE,
  TorrentCommandError.CONFLICT,
  TorrentCommandError.KEY_REUSED,
  TorrentCommandError.STALE_CURSOR -> HttpStatusCode.Conflict
  TorrentCommandError.POLICY_DENIED -> HttpStatusCode.Forbidden
  TorrentCommandError.RESOURCE_EXHAUSTED -> HttpStatusCode.TooManyRequests
  TorrentCommandError.NOT_FOUND -> HttpStatusCode.NotFound
  TorrentCommandError.STORAGE_FAILURE -> HttpStatusCode.InternalServerError
  // A client-side error; a server never raises it.
  TorrentCommandError.CONNECTION_CHANGED -> HttpStatusCode.InternalServerError
}
