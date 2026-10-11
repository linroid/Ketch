package com.linroid.ketch.remote

import com.linroid.ketch.api.ActiveConnection
import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.TaskInterest
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.TaskInterval
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class StreamInterestsTest {
  private fun tasks(ids: Set<String>?, progress: Long? = null, segments: Long? = null) =
    StreamInterest.Tasks(
      TaskInterest(ids, progress?.milliseconds, segments?.milliseconds),
    )

  @Test
  fun subscriptionOf_keepsTaskRulesOnlyWhereFasterThanEveryTask() {
    val subscription = subscriptionOf(
      listOf(
        tasks(setOf("a", "b"), progress = 400),
        tasks(setOf("b", "c"), progress = 1_000),
        tasks(setOf("d"), progress = 30_000),
      ),
      baseProgress = 10.seconds,
    )

    // 400 ms rounds up to 500; "b" keeps its shortest step; "d" is no faster than every task.
    assertEquals(
      listOf(
        TaskInterval(500, listOf("a", "b")),
        TaskInterval(1_000, listOf("c")),
        TaskInterval(10_000, null),
      ),
      subscription.progress,
    )
    assertEquals(emptyList(), subscription.segments)
  }

  @Test
  fun subscriptionOf_withoutBase_sendsOnlyWhatIsAskedFor() {
    val subscription = subscriptionOf(
      listOf(tasks(null, segments = 2_000), tasks(setOf("a"), segments = 1_000)),
      baseProgress = null,
    )

    assertEquals(emptyList(), subscription.progress)
    assertEquals(
      listOf(TaskInterval(1_000, listOf("a")), TaskInterval(2_000, null)),
      subscription.segments,
    )
    assertNull(subscription.statsIntervalMs)
    assertNull(subscription.connectionsLimit)
  }

  @Test
  fun subscriptionOf_mergesStatsTorrentsAndConnections() {
    val torrents = List(StreamEvents.MAX_TORRENTS + 2) { StreamInterest.Torrent("t$it") }
    val subscription = subscriptionOf(
      listOf(
        StreamInterest.Stats(3.seconds),
        StreamInterest.Stats(800.milliseconds),
        StreamInterest.Connections(36),
        StreamInterest.Connections(512),
        StreamInterest.Torrent("t0"),
      ) + torrents,
      baseProgress = null,
    )

    assertEquals(1_000, subscription.statsIntervalMs)
    assertEquals(512, subscription.connectionsLimit)
    assertEquals(List(StreamEvents.MAX_TORRENTS) { "t$it" }, subscription.torrents)
  }

  @Test
  fun subscriptionOf_capsTaskIds() {
    val ids = List(StreamEvents.MAX_TASK_IDS + 10) { "t$it" }.toSet()
    val subscription = subscriptionOf(listOf(tasks(ids, progress = 500)), baseProgress = null)

    assertEquals(StreamEvents.MAX_TASK_IDS, subscription.progress.single().taskIds?.size)
  }

  @Test
  fun limitedTo_keepsTheFastestInOpeningOrder() {
    fun connection(id: Long, bps: Long) = ActiveConnection(
      id = id,
      taskId = "t",
      source = "http",
      host = "example.com",
      downloadBps = bps,
      openedAt = Instant.fromEpochMilliseconds(id),
    )
    val all = ActiveConnections(
      sampledAt = Instant.fromEpochMilliseconds(0),
      connections = listOf(connection(1, 10), connection(2, 30), connection(3, 20)),
      total = 3,
      downloadBps = 60,
    )

    val limited = all.limitedTo(2)

    assertEquals(listOf(2L, 3L), limited.connections.map { it.id })
    assertEquals(3, limited.total)
    assertEquals(60, limited.downloadBps)
  }
}
