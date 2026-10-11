package com.linroid.ketch.endpoints.stream

import kotlinx.serialization.Serializable

/**
 * What a client wants on its event stream, sent whole with `PUT
 * /api/stream/{streamId}/subscription`. A stream starts at revision 0 with [tasks] only.
 *
 * @property revision counts the client's subscriptions; one not above the applied revision
 *   changes nothing
 * @property tasks the task lifecycle and state changes, starting with a snapshot of every task
 * @property progress how often downloading tasks report progress; the shortest rule that names a
 *   task wins, and tasks no rule names report it only in state changes
 * @property segments how often tasks report their segments
 * @property statsIntervalMs how often `KetchStats` are sent, or `null` for never
 * @property torrents task IDs whose torrent snapshots are sent
 * @property connectionsLimit the limit of the live connections sent, or `null` for none
 */
@Serializable
data class StreamSubscription(
  val revision: Long = 0,
  val tasks: Boolean = true,
  val progress: List<TaskInterval> = emptyList(),
  val segments: List<TaskInterval> = emptyList(),
  val statsIntervalMs: Long? = null,
  val torrents: List<String> = emptyList(),
  val connectionsLimit: Int? = null,
)

/**
 * How often the tasks with [taskIds] report a topic.
 *
 * @property intervalMs the shortest time between two reports; the server rounds it up with
 *   [StreamEvents.intervalStep]
 * @property taskIds the tasks this rule is about, or `null` for every task
 */
@Serializable
data class TaskInterval(
  val intervalMs: Long,
  val taskIds: List<String>? = null,
)

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
 *
 * @property subscription the subscription as applied, its intervals rounded up to steps
 */
@Serializable
data class StreamSubscribed(
  val revision: Long,
  val subscription: StreamSubscription,
)
