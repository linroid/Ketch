package com.linroid.ketch.endpoints.stream

import com.linroid.ketch.api.Interest
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * What a client wants on its event stream beyond the task lifecycle and state changes, which
 * every stream carries at once: how often the progress of downloading tasks comes, and the
 * [Interest]s its callers follow, each with how often. Sent whole with `PUT
 * /api/stream/{streamId}/subscription`; the server merges the interests, the shortest interval
 * winning. A stream starts at revision 0 with neither.
 *
 * @property revision counts the client's subscriptions; one not above the applied revision
 *   changes nothing
 * @property progressIntervalMs how often every downloading task's progress comes, rounded up
 *   with [StreamEvents.intervalStep], or `null` for only with its state changes
 * @property interests at most [StreamEvents.MAX_INTERESTS], naming at most
 *   [StreamEvents.MAX_TASK_IDS] tasks and [StreamEvents.MAX_TORRENTS] torrents; serialized with
 *   [StreamSerializers]
 */
@Serializable
data class StreamSubscription(
  val revision: Long = 0,
  val progressIntervalMs: Long? = null,
  val interests: List<StreamInterest> = emptyList(),
)

/**
 * An [interest] a client follows, and how often it wants its updates.
 *
 * @property interest serialized with [StreamSerializers]
 * @property intervalMs the shortest time between two updates, rounded up with
 *   [StreamEvents.intervalStep]; the first comes at once
 */
@Serializable
data class StreamInterest(
  val interest: Interest<*>,
  val intervalMs: Long,
) {
  init {
    require(intervalMs > 0) { "intervalMs must be positive" }
  }
}

/**
 * The first event of a stream.
 *
 * @property streamId names the stream in its subscription requests
 * @property protocol [StreamEvents.PROTOCOL] of the server
 * @property intervalsMs the intervals the server sends at, [StreamEvents.INTERVALS_MS]
 */
@Serializable
data class StreamHello(
  val streamId: String,
  val protocol: Int,
  val intervalsMs: List<Long>,
)

/**
 * A subscription the stream applies: the answer to a subscription request, and an event on the
 * stream before the snapshots of the topics it adds.
 */
@Serializable
data class StreamSubscribed(
  val revision: Long,
  val subscription: StreamSubscription,
)

/**
 * An interest of a kind this version does not know, as a newer client may send; the server
 * ignores it.
 */
@Serializable
@SerialName("unknown")
data object UnknownInterest : Interest<Nothing>
