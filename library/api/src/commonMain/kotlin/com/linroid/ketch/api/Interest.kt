package com.linroid.ketch.api

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Something a caller follows on a Ketch instance with [KetchApi.subscribe], reporting [T]: what
 * to follow, while `subscribe` says how often. Interests are serializable: a remote instance
 * sends its server the interests its callers hold, and the server sends only what they ask for.
 *
 * The built-in interests are nested here. Modules beside this one define their own, as they
 * define [ControlKey]s; an instance that does not know one fails it with
 * [UnsupportedOperationException] when it is collected.
 */
interface Interest<T> {
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
  }

  /**
   * Totals of the tasks that are downloading, while they change. A remote instance asks its
   * server, so they stay current however often its tasks' progress updates.
   */
  @Serializable
  @SerialName("stats")
  data object Stats : Interest<KetchStats>

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
  }

  companion object {
    /** How often [KetchApi.subscribe] reports unless asked otherwise. */
    val DEFAULT_INTERVAL: Duration = 1.seconds
  }
}
