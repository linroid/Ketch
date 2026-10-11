package com.linroid.ketch.server.stream

import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.endpoints.stream.StreamError
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamSubscribed
import com.linroid.ketch.endpoints.stream.StreamSubscription
import com.linroid.ketch.server.api.toErrorResponse
import com.linroid.ketch.torrent.api.TorrentCommandException
import com.linroid.ketch.torrent.api.TorrentInterest
import com.linroid.ketch.torrent.api.TorrentSnapshot
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.transformWhile
import java.util.UUID
import kotlin.coroutines.cancellation.CancellationException
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private val log = KetchLogger("EventHub")

/** How many event streams a server serves at once. */
internal const val MAX_EVENT_STREAMS = 32

/**
 * The interval the hub asks the engine for torrents and connections at: as often as it reports
 * them; each stream sends them as often as its own subscription asks.
 */
private val INTERNAL_INTERVAL = StreamEvents.INTERVALS_MS.first().milliseconds

/**
 * The event streams of a server: every stream reads the same [TaskFrame]s, kept by one
 * [TaskTracker] while any stream is open, and the same torrent and connection producers, run
 * while any stream subscribes to them. See [EventStream] for what one stream sends.
 */
internal class EventHub(
  private val ketch: KetchApi,
  private val scope: CoroutineScope,
  private val timeSource: TimeSource.WithComparableMarks = TimeSource.Monotonic,
  val maxStreams: Int = MAX_EVENT_STREAMS,
) {
  private val lock = Any()
  private val streams = HashMap<String, EventStream>()
  private var tracker: TaskTracker? = null
  private val torrents = SharedSource(scope, ::torrentItems)
  private val connections = SharedSource(scope, ::connectionItems)

  /** Streams that are open. */
  val openStreams: Int get() = synchronized(lock) { streams.size }

  /** Whether the tasks are followed, which they are while a stream is open; for tests. */
  internal val isTracking: Boolean get() = synchronized(lock) { tracker != null }

  /** Torrent task IDs whose snapshots are followed; for tests. */
  internal val followedTorrents: Set<String> get() = torrents.activeKeys

  /**
   * Opens a stream for the caller with [owner] (what identifies its credentials, or `null`
   * without any), or returns `null` when [maxStreams] are open. The stream starts with the
   * task lifecycle; [EventStream.run] sends its events.
   */
  fun open(owner: String?): EventStream? = synchronized(lock) {
    if (streams.size >= maxStreams) return null
    val tracker = tracker ?: TaskTracker(ketch.tasks, scope).also { tracker = it }
    val id = UUID.randomUUID().toString()
    val parent = scope.coroutineContext[Job]
    val streamScope = CoroutineScope(scope.coroutineContext + SupervisorJob(parent))
    val stream = EventStream(
      id = id,
      owner = owner,
      tracker = tracker,
      torrents = torrents,
      connections = connections,
      timeSource = timeSource,
      scope = streamScope,
      onClose = ::closed,
    )
    streams[id] = stream
    log.d { "Opened event stream, ${streams.size} open" }
    stream
  }

  /**
   * Applies [subscription] to the stream with [streamId], if [owner] opened it.
   *
   * @throws UnknownStreamException when there is no such stream of [owner]
   * @throws IllegalArgumentException when [subscription] goes beyond the limits of
   *   [StreamEvents]
   */
  fun subscribe(
    streamId: String,
    owner: String?,
    subscription: StreamSubscription,
  ): StreamSubscribed {
    val stream = synchronized(lock) { streams[streamId] }
    if (stream == null || stream.owner != owner) throw UnknownStreamException()
    return stream.apply(subscription, planOf(subscription))
  }

  private fun closed(stream: EventStream) {
    synchronized(lock) {
      if (streams.remove(stream.id) == null) return
      if (streams.isEmpty()) {
        tracker?.stop()
        tracker = null
      }
      log.d { "Closed event stream, ${streams.size} open" }
    }
  }

  private fun torrentItems(taskId: String): Flow<SourceItem<TorrentSnapshot>> =
    ketch.subscribe(TorrentInterest(taskId), INTERNAL_INTERVAL)
      .transformWhile { snapshot ->
        emit(if (snapshot == null) SourceItem.Removed else SourceItem.Value(snapshot))
        snapshot != null
      }
      .catch { error ->
        if (error is CancellationException) throw error
        val failure = when (error) {
          is TorrentCommandException -> {
            val response = error.toErrorResponse()
            StreamError(response.error, response.message, StreamEvents.TOPIC_TORRENTS, taskId)
          }
          is UnsupportedOperationException -> StreamError(
            StreamEvents.UNSUPPORTED,
            "This server has no torrent controls",
            StreamEvents.TOPIC_TORRENTS,
            taskId,
          )
          else -> {
            log.w { "Torrent events of taskId=$taskId failed: ${error.describeCauses()}" }
            StreamError(
              StreamEvents.INTERNAL_ERROR,
              "Torrent events are unavailable",
              StreamEvents.TOPIC_TORRENTS,
              taskId,
            )
          }
        }
        emit(SourceItem.Failed(failure))
      }

  private fun connectionItems(limit: Int): Flow<SourceItem<ActiveConnections>> =
    ketch.subscribe(Interest.Connections(limit), INTERNAL_INTERVAL)
      .map<ActiveConnections, SourceItem<ActiveConnections>> { SourceItem.Value(it) }
      .catch { error ->
        if (error is CancellationException) throw error
        val failure = if (error is UnsupportedOperationException) {
          StreamError(
            StreamEvents.UNSUPPORTED,
            error.message ?: "This server does not report its connections",
            StreamEvents.TOPIC_CONNECTIONS,
          )
        } else {
          log.w { "Connections stream failed: ${error.describeCauses()}" }
          StreamError(
            StreamEvents.INTERNAL_ERROR,
            "Live connections are unavailable",
            StreamEvents.TOPIC_CONNECTIONS,
          )
        }
        emit(SourceItem.Failed(failure))
      }
}
