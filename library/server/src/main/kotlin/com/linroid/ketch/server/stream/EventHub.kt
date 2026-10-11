package com.linroid.ketch.server.stream

import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamSubscribed
import com.linroid.ketch.endpoints.stream.StreamSubscription
import com.linroid.ketch.server.api.ServerJson
import com.linroid.ketch.server.api.toErrorResponse
import com.linroid.ketch.torrent.api.TorrentCommandException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.util.UUID
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.TimeSource

private val log = KetchLogger("EventHub")

/** How many event streams a server serves at once. */
internal const val MAX_EVENT_STREAMS = 32

/**
 * The interval the hub asks the engine for interests at: as often as it reports them; each
 * stream sends them as often as its own subscription asks.
 */
private val INTERNAL_INTERVAL = StreamEvents.INTERVALS_MS.first().milliseconds

/**
 * The event streams of a server: every stream reads the same [TaskFrame]s, kept by one
 * [TaskTracker] while any stream is open, and one [KetchApi.subscribe] per interest, run while
 * any stream follows it, each value encoded once. See [EventStream] for what one stream sends.
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
  private val sources = SharedSource(scope, ::valuesOf, ::failureOf)

  /** Streams that are open. */
  val openStreams: Int get() = synchronized(lock) { streams.size }

  /** Whether the tasks are followed, which they are while a stream is open; for tests. */
  internal val isTracking: Boolean get() = synchronized(lock) { tracker != null }

  /** Interests the engine reports to the streams; for tests. */
  internal val followedInterests: Set<Interest<*>> get() = sources.activeKeys

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
      sources = sources,
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

  /** The values the engine reports for [interest], each encoded as clients decode it. */
  private fun valuesOf(interest: Interest<*>): Flow<String> {
    @Suppress("UNCHECKED_CAST")
    val typed = interest as Interest<Any?>
    return ketch.subscribe(typed, INTERNAL_INTERVAL)
      .map { ServerJson.encodeToString(typed.valueSerializer, it) }
  }

  private fun failureOf(interest: Interest<*>, error: Throwable): TopicEnd.Failed = when (error) {
    is TorrentCommandException -> {
      val response = error.toErrorResponse()
      TopicEnd.Failed(response.error, response.message)
    }
    is UnsupportedOperationException -> TopicEnd.Failed(
      StreamEvents.UNSUPPORTED,
      error.message ?: "This server does not report the interest",
    )
    else -> {
      log.w { "Following $interest failed: ${error.describeCauses()}" }
      TopicEnd.Failed(StreamEvents.INTERNAL_ERROR, "The interest is unavailable")
    }
  }
}
