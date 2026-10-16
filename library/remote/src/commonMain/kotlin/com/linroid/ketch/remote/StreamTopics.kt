package com.linroid.ketch.remote

import com.linroid.ketch.endpoints.stream.InterestError
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamSubscription
import io.ktor.http.HttpStatusCode
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.json.JsonElement

/**
 * What the event stream sent last for an interest: its latest value and how it ended. A reader
 * that skips states still sees the last value before the end.
 *
 * @property sequence orders every value the stream sent, so a reader tells a new one from the one
 *   it emitted, also after the interest was dropped and followed again
 * @property value the latest value, or `null` before the first
 * @property end how the interest ended, or `null` while it runs
 */
internal data class TopicState(
  val sequence: Long = 0,
  val value: JsonElement? = null,
  val end: TopicEnd? = null,
)

/** How an interest ended on the event stream. */
internal sealed interface TopicEnd {
  /** What it follows is gone, such as a removed torrent. */
  data object Completed : TopicEnd

  /** The server cannot serve it, or it failed. */
  data class Failed(val error: InterestError) : TopicEnd
}

/** Thrown to an interest's collector when the event stream it was reading closed. */
internal class StreamLostException : IllegalStateException("The connection to the server was lost")

/** Thrown to an interest's collector when the server cannot serve it, or it failed. */
internal class InterestFailedException(val error: InterestError) :
  IllegalStateException("The server failed the interest: ${error.error}")

/**
 * The latest state of each interest the event stream of a [RemoteKetch] sends, by the ID its
 * subscription gave it. Called only by the reader of the stream.
 */
internal class StreamTopics {
  /** Counts the streams; the one that is open, or `null` while none is. */
  val live = MutableStateFlow<Long?>(null)

  /** The interests of the applied subscription, by ID; those without a value yet are left out. */
  val states = MutableStateFlow<Map<Long, TopicState>>(emptyMap())

  private var applied: Set<Long> = emptySet()
  private var sequence = 0L

  /** A stream with [generation] opened: what the previous one sent is no longer current. */
  fun opened(generation: Long) {
    applied = emptySet()
    states.value = emptyMap()
    live.value = generation
  }

  fun closed() {
    live.value = null
  }

  /**
   * The stream applies [subscription]: the states of interests it no longer names are forgotten,
   * and events for them are ignored, so a caller following them again waits for current ones.
   */
  fun applied(subscription: StreamSubscription) {
    applied = subscription.interests.mapTo(HashSet()) { it.id }
    states.update { current -> current.filterKeys { it in applied } }
  }

  fun value(id: Long, value: JsonElement) {
    if (id !in applied) return
    val next = ++sequence
    states.update { it + (id to TopicState(next, value)) }
  }

  fun ended(id: Long, end: TopicEnd) {
    if (id !in applied) return
    states.update { it + (id to (it[id] ?: TopicState()).copy(end = end)) }
  }
}

/**
 * The exception callers of an interest get when the server cannot serve it
 * ([UnsupportedOperationException]) or it failed ([RemoteApiException]).
 */
internal fun InterestError.toException(): Exception = when (error) {
  StreamEvents.UNSUPPORTED -> UnsupportedOperationException(message)
  else -> RemoteApiException(HttpStatusCode.InternalServerError.value, error, message)
}
