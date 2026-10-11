package com.linroid.ketch.endpoints.stream

import com.linroid.ketch.api.Interest
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.NothingSerializer

/**
 * What a client wants on its event stream beyond the task lifecycle and state changes, which
 * every stream carries at once: how often the progress of downloading tasks comes, which tasks'
 * segments come and how often, and the [Interest]s its callers follow. Sent whole with `PUT
 * /api/stream/{streamId}/subscription`. A stream starts at revision 0 with none of them.
 *
 * @property revision counts the client's subscriptions; one not above the applied revision
 *   changes nothing
 * @property progressIntervalMs how often every downloading task's progress comes, rounded up
 *   with [StreamEvents.intervalStep], or `null` for only with its state changes
 * @property segments whose segments come as task events, at most [StreamEvents.MAX_INTERESTS]
 *   entries naming at most [StreamEvents.MAX_TASK_IDS] tasks; where several name a task, the
 *   shortest interval wins
 * @property interests at most [StreamEvents.MAX_INTERESTS], with distinct IDs; serialized with
 *   [StreamSerializers]
 */
@Serializable
data class StreamSubscription(
  val revision: Long = 0,
  val progressIntervalMs: Long? = null,
  val segments: List<StreamSegments> = emptyList(),
  val interests: List<StreamInterest> = emptyList(),
)

/**
 * Tasks whose segments come as `task.segments` events, at most once per [intervalMs] each: the
 * part of a task that only comes when asked for.
 *
 * @property intervalMs the shortest time between two updates of a task, rounded up with
 *   [StreamEvents.intervalStep]; the first comes at once
 */
@Serializable
data class StreamSegments(
  val taskIds: Set<String>,
  val intervalMs: Long,
) {
  init {
    require(taskIds.isNotEmpty()) { "taskIds must not be empty" }
    require(intervalMs > 0) { "intervalMs must be positive" }
  }
}

/**
 * An [interest] a client follows, and how often it wants its updates. Its events carry [id]; an
 * ID names one interest for the life of the stream.
 *
 * @property interest serialized with [StreamSerializers]
 * @property intervalMs the shortest time between two values, rounded up with
 *   [StreamEvents.intervalStep]; the first comes at once, and the end or a failure never waits
 */
@Serializable
data class StreamInterest(
  val id: Long,
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
 * stream before the events of the interests it adds.
 */
@Serializable
data class StreamSubscribed(
  val revision: Long,
  val subscription: StreamSubscription,
)

/**
 * An interest of a kind this version does not know, as a newer client may send; the server
 * answers it with an `unsupported` [InterestError].
 */
@Serializable
@SerialName("unknown")
data object UnknownInterest : Interest<Nothing> {
  @OptIn(ExperimentalSerializationApi::class)
  override val valueSerializer: KSerializer<Nothing> get() = NothingSerializer()
}
