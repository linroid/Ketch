package com.linroid.ketch.api

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Something a caller follows on a Ketch instance with [KetchApi.subscribe], reporting [T]: what
 * to follow, while `subscribe` says how often. Interests are serializable: a remote instance
 * sends its server the interests its callers hold, and the server sends only what they ask for.
 *
 * Its flow ends when what it follows is gone, as a removed torrent's does, and fails when the
 * instance cannot report it: an instance that does not know an interest fails it with
 * [UnsupportedOperationException] when it is collected.
 *
 * The built-in interests are nested here. Modules beside this one define their own, as they
 * define [ControlKey]s.
 */
interface Interest<T> {
  /**
   * Encodes what this interest reports, as a server sends it to remote instances. Not part of the
   * interest's own serialized form.
   */
  val valueSerializer: KSerializer<T>

  /**
   * The segments of the tasks with [taskIds], by task ID; they also update on
   * [DownloadTask.segments]. Segments are the one part of a task that only comes when asked for:
   * its lifecycle, state changes and progress come anyway. A torrent has one per file.
   *
   * @property taskIds the tasks, at most 1024
   */
  @Serializable
  @SerialName("segments")
  data class Segments(val taskIds: Set<String>) : Interest<Map<String, List<Segment>>> {
    init {
      require(taskIds.isNotEmpty()) { "taskIds must not be empty" }
    }

    override val valueSerializer: KSerializer<Map<String, List<Segment>>>
      get() = MapSerializer(String.serializer(), ListSerializer(Segment.serializer()))
  }

  /**
   * Totals of the tasks that are downloading, while they change. A remote instance asks its
   * server, so they stay current however often its tasks' progress updates.
   */
  @Serializable
  @SerialName("stats")
  data object Stats : Interest<KetchStats> {
    override val valueSerializer: KSerializer<KetchStats> get() = KetchStats.serializer()
  }

  /**
   * Live network connections of every task: HTTP requests, FTP transfers, media fetches and
   * torrent peers, sampled once a second while followed. Reports a snapshot right away, then one
   * each time something changes (rates, opens, closes).
   *
   * Check that [KetchStatus.features] lists [KetchFeatures.ACTIVE_CONNECTIONS] first; without
   * it, collecting fails with [UnsupportedOperationException].
   *
   * @property limit the most connections a snapshot holds, in 1..[ActiveConnections.MAX_LIMIT]
   */
  @Serializable
  @SerialName("connections")
  data class Connections(val limit: Int = ActiveConnections.DEFAULT_LIMIT) :
    Interest<ActiveConnections> {
    init {
      require(limit in 1..ActiveConnections.MAX_LIMIT) {
        "limit must be in 1..${ActiveConnections.MAX_LIMIT}"
      }
    }

    override val valueSerializer: KSerializer<ActiveConnections>
      get() = ActiveConnections.serializer()
  }

  companion object {
    /** How often [KetchApi.subscribe] reports unless asked otherwise. */
    val DEFAULT_INTERVAL: Duration = 1.seconds
  }
}
