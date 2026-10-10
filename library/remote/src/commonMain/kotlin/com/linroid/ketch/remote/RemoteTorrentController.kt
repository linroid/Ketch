package com.linroid.ketch.remote

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.torrent.TorrentCapabilities
import com.linroid.ketch.api.torrent.TorrentCapability
import com.linroid.ketch.api.torrent.TorrentCommandContext
import com.linroid.ketch.api.torrent.TorrentCommandError
import com.linroid.ketch.api.torrent.TorrentCommandException
import com.linroid.ketch.api.torrent.TorrentCommandResult
import com.linroid.ketch.api.torrent.TorrentController
import com.linroid.ketch.api.torrent.TorrentFileOrder
import com.linroid.ketch.api.torrent.TorrentFilePage
import com.linroid.ketch.api.torrent.TorrentPageRequest
import com.linroid.ketch.api.torrent.TorrentRevision
import com.linroid.ketch.api.torrent.TorrentRevisionDecision
import com.linroid.ketch.api.torrent.TorrentSnapshot
import com.linroid.ketch.api.torrent.compareIncoming
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.model.TorrentSeedingRequest
import com.linroid.ketch.endpoints.model.TorrentSelectionRequest
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.put
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsChannel
import io.ktor.client.statement.HttpResponse
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.readUTF8Line
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json

/**
 * [TorrentController] of a Ketch server, over its `/api/torrents` routes. It fails closed: a
 * server without the routes, or without a capability, refuses every command with
 * [TorrentCommandError.UNSUPPORTED] before any request is sent. A response that arrives after
 * the connection to the server changed ([generation]) is discarded with
 * [TorrentCommandError.CONNECTION_CHANGED]; retrying with the same idempotency key returns the
 * outcome the server recorded.
 *
 * @param generation counts the connections to the server; it changes on every reconnect
 */
internal class RemoteTorrentController(
  private val httpClient: HttpClient,
  private val generation: StateFlow<Long>,
  private val json: Json,
) : TorrentController {
  private val log = KetchLogger("RemoteTorrents")
  private val mutex = Mutex()
  private var cached: Pair<Long, TorrentCapabilities>? = null

  override suspend fun capabilities(): TorrentCapabilities {
    val current = generation.value
    mutex.withLock { cached?.takeIf { it.first == current }?.let { return it.second } }
    val response = httpClient.get(Api.Torrents.Capabilities())
    val capabilities = if (response.status.isSuccess()) {
      response.body<TorrentCapabilities>()
    } else {
      val body = response.errorResponse()
      if (body?.error != null || !response.status.isMissingRoute()) {
        throw response.toRemoteApiException(body)
      }
      log.d { "The server has no torrent controls (HTTP ${response.status.value})" }
      TorrentCapabilities()
    }
    mutex.withLock { if (generation.value == current) cached = current to capabilities }
    return capabilities
  }

  override suspend fun snapshot(taskId: String): TorrentSnapshot? {
    requireCapability(TorrentCapability.INSPECT)
    return call(notFoundIsNull = true) { httpClient.get(Api.Torrents.ById(id = taskId)) }
  }

  override fun observe(taskId: String): Flow<TorrentSnapshot?> = flow {
    requireCapability(TorrentCapability.INSPECT)
    val connection = generation.value
    var current: TorrentRevision? = null
    var ended = false
    // Read as plain text: the stream is simple, and any engine can serve it.
    httpClient.prepareGet("/api/torrents/${taskId.encodeURLPathPart()}/events") {
      header(HttpHeaders.Accept, "text/event-stream")
    }.execute { response ->
      if (!response.status.isSuccess()) {
        val body = response.errorResponse()
        throw response.toCommandException(body) ?: response.toRemoteApiException(body)
      }
      val channel = response.bodyAsChannel()
      var name: String? = null
      val data = StringBuilder()
      while (!ended) {
        val line = channel.readUTF8Line()
        // A blank line ends an event; so does the end of the stream, leniently.
        if (line.isNullOrEmpty()) {
          val event = name
          name = null
          if (event != null) {
            if (generation.value != connection) throw connectionChanged()
            ended = event == EVENT_REMOVED || event == EVENT_ERROR
            when (event) {
              EVENT_SNAPSHOT -> {
                val snapshot = json.decodeFromString<TorrentSnapshot>(data.toString())
                when (current?.compareIncoming(snapshot.revision)) {
                  null, TorrentRevisionDecision.APPLY -> {
                    current = snapshot.revision
                    emit(snapshot)
                  }
                  TorrentRevisionDecision.IGNORE -> {}
                  TorrentRevisionDecision.RESYNC -> throw connectionChanged()
                }
              }
              EVENT_REMOVED -> emit(null)
              EVENT_ERROR -> {
                val body = json.decodeFromString<ErrorResponse>(data.toString())
                throw TorrentCommandException(
                  error = TorrentCommandError.fromWire(body.error)
                    ?: TorrentCommandError.UNSUPPORTED,
                  message = body.message,
                  currentRevision = body.revision,
                )
              }
            }
          }
          data.clear()
          if (line == null) break
          continue
        }
        if (line.startsWith(":")) continue
        val value = line.substringAfter(':', "").removePrefix(" ")
        when (line.substringBefore(':')) {
          "event" -> name = value
          "data" -> {
            if (data.isNotEmpty()) data.append('\n')
            data.append(value)
          }
        }
      }
    }
    // A stream that ends without saying why lost its connection.
    if (!ended) throw connectionChanged()
  }

  override suspend fun files(
    taskId: String,
    page: TorrentPageRequest,
    order: TorrentFileOrder,
    descending: Boolean,
  ): TorrentFilePage? {
    requireCapability(TorrentCapability.INSPECT)
    return call(notFoundIsNull = true) {
      httpClient.get(
        Api.Torrents.ById.Files(
          parent = Api.Torrents.ById(id = taskId),
          limit = page.limit,
          cursor = page.cursor,
          sort = order.wireName.takeIf { order != TorrentFileOrder.TORRENT },
          desc = descending.takeIf { it },
        )
      )
    }
  }

  override suspend fun select(
    taskId: String,
    fileIds: Set<String>,
    context: TorrentCommandContext,
  ): TorrentCommandResult {
    requireCapability(TorrentCapability.FILE_SELECTION)
    return call(notFoundIsNull = false) {
      httpClient.put(Api.Torrents.ById.Selection(parent = Api.Torrents.ById(id = taskId))) {
        contentType(ContentType.Application.Json)
        setBody(TorrentSelectionRequest(fileIds, context))
      }
    } ?: throw IllegalStateException("Empty response")
  }

  override suspend fun setSeeding(
    taskId: String,
    seeding: Boolean,
    context: TorrentCommandContext,
  ): TorrentCommandResult {
    // Whether the server seeds now is its decision (policy_denied), not a capability to check.
    requireCapability(TorrentCapability.INSPECT)
    return call(notFoundIsNull = false) {
      httpClient.put(Api.Torrents.ById.Seeding(parent = Api.Torrents.ById(id = taskId))) {
        contentType(ContentType.Application.Json)
        setBody(TorrentSeedingRequest(seeding, context))
      }
    } ?: throw IllegalStateException("Empty response")
  }

  /** Fails with [TorrentCommandError.UNSUPPORTED], sending nothing, unless the server has it. */
  private suspend fun requireCapability(capability: TorrentCapability) {
    if (!capabilities().supports(capability)) {
      throw TorrentCommandException(
        TorrentCommandError.UNSUPPORTED,
        "This server cannot do that: ${capability.wireName} is unavailable",
      )
    }
  }

  /**
   * Sends [request] and reads its body as [T], discarding a response that arrived after the
   * connection changed. Errors with a torrent wire name become [TorrentCommandException]s;
   * `not_found` is `null` when [notFoundIsNull].
   */
  private suspend inline fun <reified T> call(
    notFoundIsNull: Boolean,
    request: () -> HttpResponse,
  ): T? {
    val connection = generation.value
    val response = request()
    if (generation.value != connection) throw connectionChanged()
    if (response.status.isSuccess()) return response.body<T>()
    val body = response.errorResponse()
    if (notFoundIsNull && body?.error == TorrentCommandError.NOT_FOUND.wireName) return null
    throw response.toCommandException(body) ?: response.toRemoteApiException(body)
  }

  /**
   * The [TorrentCommandException] this unsuccessful response reports: its torrent error code, or
   * [TorrentCommandError.UNSUPPORTED] from a server without the route. `null` for other errors.
   */
  private fun HttpResponse.toCommandException(body: ErrorResponse?): TorrentCommandException? {
    val error = TorrentCommandError.fromWire(body?.error)
    return when {
      error != null -> TorrentCommandException(
        error = error,
        message = body?.message ?: error.wireName,
        currentRevision = body?.revision,
        cause = toRemoteApiException(body),
      )
      body == null && status.isMissingRoute() -> TorrentCommandException(
        TorrentCommandError.UNSUPPORTED,
        "This server has no torrent controls",
      )
      else -> null
    }
  }

  private fun HttpStatusCode.isMissingRoute() =
    this == HttpStatusCode.NotFound || this == HttpStatusCode.MethodNotAllowed

  private fun connectionChanged() = TorrentCommandException(
    TorrentCommandError.CONNECTION_CHANGED,
    "The connection to the server changed; ask again",
  )

  private companion object {
    const val EVENT_SNAPSHOT = "snapshot"
    const val EVENT_REMOVED = "removed"
    const val EVENT_ERROR = "error"
  }
}
