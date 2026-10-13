package com.linroid.ketch.remote

import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.endpoints.model.ErrorResponse
import com.linroid.ketch.endpoints.torrent.TorrentSeedingRequest
import com.linroid.ketch.endpoints.torrent.TorrentSelectionRequest
import com.linroid.ketch.endpoints.torrent.Torrents
import com.linroid.ketch.torrent.api.TorrentCapabilities
import com.linroid.ketch.torrent.api.TorrentCapability
import com.linroid.ketch.torrent.api.TorrentCommandContext
import com.linroid.ketch.torrent.api.TorrentCommandError
import com.linroid.ketch.torrent.api.TorrentCommandException
import com.linroid.ketch.torrent.api.TorrentCommandResult
import com.linroid.ketch.torrent.api.TorrentController
import com.linroid.ketch.torrent.api.TorrentFileOrder
import com.linroid.ketch.torrent.api.TorrentFilePage
import com.linroid.ketch.torrent.api.TorrentInterest
import com.linroid.ketch.torrent.api.TorrentPageRequest
import com.linroid.ketch.torrent.api.TorrentReachability
import com.linroid.ketch.torrent.api.TorrentRevision
import com.linroid.ketch.torrent.api.TorrentRevisionDecision
import com.linroid.ketch.torrent.api.TorrentSnapshot
import com.linroid.ketch.torrent.api.compareIncoming
import io.ktor.client.HttpClient
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.put
import io.ktor.client.request.header
import io.ktor.client.request.prepareGet
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import io.ktor.http.encodeURLPathPart
import io.ktor.http.isSuccess
import io.ktor.utils.io.LineEnding
import io.ktor.utils.io.readLine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.channelFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlin.time.Duration

/**
 * [TorrentController] of a Ketch server, over its `/api/torrents` routes. It fails closed: a
 * server without the routes, or without a capability, refuses every command with
 * [TorrentCommandError.UNSUPPORTED] before any request is sent. A response that arrives after
 * the connection to the server changed ([generation]) is discarded with
 * [TorrentCommandError.CONNECTION_CHANGED]; retrying with the same idempotency key returns the
 * outcome the server recorded.
 *
 * @param generation counts the connections to the server; it changes on every reconnect
 * @param streamed whether the client reads the server's event stream: snapshots then come from
 *   [streamTorrent] rather than a stream of their own
 * @param streamTorrent the snapshots the event stream sends for an interest, ending when the
 *   server ends it; it fails with [InterestFailedException] or [StreamLostException]
 */
internal class RemoteTorrentController(
  private val httpClient: HttpClient,
  private val generation: StateFlow<Long>,
  private val json: Json,
  private val streamed: StateFlow<Boolean> = MutableStateFlow(false),
  private val streamTorrent: (TorrentInterest, Duration) -> Flow<TorrentSnapshot?> =
    { _, _ -> emptyFlow() },
) : TorrentController {
  private val log = KetchLogger("RemoteTorrents")
  private val mutex = Mutex()
  private var cached: Pair<Long, TorrentCapabilities>? = null

  override suspend fun capabilities(): TorrentCapabilities {
    val current = generation.value
    mutex.withLock { cached?.takeIf { it.first == current }?.let { return it.second } }
    val response = httpClient.get(Torrents.Capabilities())
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
    // Capabilities of a previous connection would gate commands on the new one.
    mutex.withLock {
      if (generation.value != current) throw connectionChanged()
      cached = current to capabilities
    }
    return capabilities
  }

  override suspend fun reachability(): TorrentReachability {
    requireCapability(TorrentCapability.REACHABILITY)
    return checkNotNull(call<TorrentReachability>(notFoundIsNull = false) {
      httpClient.get(Torrents.Reachability())
    })
  }

  override suspend fun snapshot(taskId: String): TorrentSnapshot? {
    requireCapability(TorrentCapability.INSPECT)
    return call(notFoundIsNull = true) { httpClient.get(Torrents.ById(id = taskId)) }
  }

  /** What `TorrentInterest` reports about every [interval]; see there. */
  fun observe(
    taskId: String,
    interval: Duration = Interest.DEFAULT_INTERVAL,
  ): Flow<TorrentSnapshot?> = flow {
    requireCapability(TorrentCapability.INSPECT)
    if (streamed.value) {
      emitAll(observeOnStream(taskId, interval))
      return@flow
    }
    // Engines such as Darwin's run execute's block on another dispatcher, where a plain flow may
    // not emit; the stream is read in a channel flow and its outcome is replayed here in order,
    // so a snapshot read just before a failure is still delivered.
    streamEvents(taskId).collect { event ->
      when (event) {
        is StreamEvent.Update -> emit(event.snapshot)
        is StreamEvent.Failed -> throw event.error
      }
    }
  }

  /** The snapshots of [taskId] the event stream sends, under the same rules as its own stream. */
  private fun observeOnStream(taskId: String, interval: Duration): Flow<TorrentSnapshot?> = flow {
    var current: TorrentRevision? = null
    var gone = false
    try {
      streamTorrent(TorrentInterest(taskId), interval).collect { snapshot ->
        if (snapshot == null) {
          gone = true
          emit(null)
          return@collect
        }
        when (current?.compareIncoming(snapshot.revision)) {
          null, TorrentRevisionDecision.APPLY -> {
            current = snapshot.revision
            emit(snapshot)
          }
          TorrentRevisionDecision.IGNORE -> {}
          TorrentRevisionDecision.RESYNC -> throw connectionChanged()
        }
      }
      // The server ends the interest once the torrent is gone.
      if (!gone) emit(null)
    } catch (e: InterestFailedException) {
      throw TorrentCommandException(
        error = TorrentCommandError.fromWire(e.error.error) ?: TorrentCommandError.UNSUPPORTED,
        message = e.error.message,
      )
    } catch (_: StreamLostException) {
      throw connectionChanged()
    }
  }

  private fun streamEvents(taskId: String): Flow<StreamEvent> = channelFlow {
    try {
      readEvents(taskId) { send(StreamEvent.Update(it)) }
    } catch (e: CancellationException) {
      throw e
    } catch (e: Throwable) {
      send(StreamEvent.Failed(e))
    }
  }

  private sealed interface StreamEvent {
    class Update(val snapshot: TorrentSnapshot?) : StreamEvent
    class Failed(val error: Throwable) : StreamEvent
  }

  private suspend fun readEvents(taskId: String, deliver: suspend (TorrentSnapshot?) -> Unit) {
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
        val line = channel.readLine(LineEnding.Lenient)
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
                    deliver(snapshot)
                  }
                  TorrentRevisionDecision.IGNORE -> {}
                  TorrentRevisionDecision.RESYNC -> throw connectionChanged()
                }
              }
              EVENT_REMOVED -> deliver(null)
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
        Torrents.ById.Files(
          parent = Torrents.ById(id = taskId),
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
      httpClient.put(Torrents.ById.Selection(parent = Torrents.ById(id = taskId))) {
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
      httpClient.put(Torrents.ById.Seeding(parent = Torrents.ById(id = taskId))) {
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
