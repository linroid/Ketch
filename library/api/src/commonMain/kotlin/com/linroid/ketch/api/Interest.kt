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
 * What [KetchApi.subscribe] follows, reporting [T]. Serializable, so remote instances can send
 * it to their server. Its flow ends when what it follows is gone and fails with
 * [UnsupportedOperationException] on instances that do not know it. Other modules define their
 * own beside the built-in ones nested here.
 */
interface Interest<T> {
  /** Encodes what this interest reports, as servers send it. */
  val valueSerializer: KSerializer<T>

  /**
   * The [segments][DownloadTask.segments] of [taskIds] (at most 1024), by task ID. Unlike state
   * and progress, remote segments only come when followed.
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

  /** Totals of the downloading tasks. */
  @Serializable
  @SerialName("stats")
  data object Stats : Interest<KetchStats> {
    override val valueSerializer: KSerializer<KetchStats> get() = KetchStats.serializer()
  }

  /**
   * Live network connections of every task, sampled each second: a snapshot at once, then one
   * per change. Needs [KetchFeatures.ACTIVE_CONNECTIONS].
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
    /** [KetchApi.subscribe]'s default interval. */
    val DEFAULT_INTERVAL: Duration = 1.seconds
  }
}
