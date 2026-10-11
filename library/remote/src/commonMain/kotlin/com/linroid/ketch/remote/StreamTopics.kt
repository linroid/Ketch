package com.linroid.ketch.remote

import com.linroid.ketch.api.ActiveConnection
import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchStats
import com.linroid.ketch.endpoints.stream.StreamError
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamSubscription
import com.linroid.ketch.torrent.api.TorrentInterest
import com.linroid.ketch.torrent.api.TorrentSnapshot
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/** An item of a topic the event stream sends: a value, the end of its subject, or a failure. */
internal sealed interface TopicItem<out T> {
  data class Value<T>(val value: T) : TopicItem<T>

  /** The subject is gone, such as a removed torrent; nothing follows. */
  data object Removed : TopicItem<Nothing>

  /** The topic failed on the server; nothing follows. */
  data class Failed(val error: StreamError) : TopicItem<Nothing>
}

/** Thrown to a topic's collector when the event stream it was reading closed. */
internal class StreamLostException : IllegalStateException("The connection to the server was lost")

/**
 * The latest item of each topic beyond the tasks that the event stream of a [RemoteKetch]
 * sends, for the callers that asked for it.
 */
internal class StreamTopics {
  /** Counts the streams; the one that is open, or `null` while none is. */
  val live = MutableStateFlow<Long?>(null)
  val stats = MutableStateFlow<KetchStats?>(null)
  val torrents = MutableStateFlow<Map<String, TopicItem<TorrentSnapshot>>>(emptyMap())
  val connections = MutableStateFlow<TopicItem<ActiveConnections>?>(null)

  /** A stream with [generation] opened: what the previous one sent is no longer current. */
  fun opened(generation: Long) {
    stats.value = null
    torrents.value = emptyMap()
    connections.value = null
    live.value = generation
  }

  fun closed() {
    live.value = null
  }

  /**
   * The stream applies [subscription]: items of topics it no longer sends are forgotten, so a
   * caller asking for them again waits for current ones.
   */
  fun applied(subscription: StreamSubscription) {
    val interests = subscription.interests.map { it.interest }
    if (interests.none { it == Interest.Stats }) stats.value = null
    if (interests.none { it is Interest.Connections }) connections.value = null
    val followed = interests.mapNotNullTo(HashSet()) { (it as? TorrentInterest)?.taskId }
    torrents.update { items -> items.filterKeys { it in followed } }
  }

  fun torrent(taskId: String, item: TopicItem<TorrentSnapshot>) {
    torrents.update { it + (taskId to item) }
  }
}

/**
 * At most [limit] of these connections, the fastest by combined rate, ordered by when they
 * opened, as a server would have chosen them with that limit; the totals stay.
 */
internal fun ActiveConnections.limitedTo(limit: Int): ActiveConnections {
  if (connections.size <= limit) return this
  val byAge = compareBy<ActiveConnection>({ it.openedAt }, { it.id })
  val byRate = compareByDescending<ActiveConnection> { it.downloadBps + it.uploadBps }.then(byAge)
  return copy(connections = connections.sortedWith(byRate).take(limit).sortedWith(byAge))
}

/** The failure of the connections topic, as `activeConnections` reports it. */
internal fun StreamError.toConnectionsFailure(): Exception = when (error) {
  StreamEvents.UNSUPPORTED -> UnsupportedOperationException(message)
  else -> RemoteApiException(HttpStatusCode.InternalServerError.value, error, message)
}
