package com.linroid.ketch.remote

import com.linroid.ketch.api.ActiveConnection
import com.linroid.ketch.api.ActiveConnections
import com.linroid.ketch.api.Interest
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamInterest
import com.linroid.ketch.torrent.api.TorrentInterest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

class StreamInterestsTest {
  @Test
  fun subscriptionOf_mergesSegmentsByStep_eachTaskAtItsShortest() {
    val subscription = subscriptionOf(
      listOf(
        StreamInterest(Interest.Segments(setOf("a", "b")), 400),
        StreamInterest(Interest.Segments(setOf("b", "c")), 1_000),
        StreamInterest(Interest.Segments(setOf("c")), 900),
      ),
      progress = 1.seconds,
    )

    // 400 ms rounds up to 500 ms, 900 ms to 1 s.
    assertEquals(1_000, subscription.progressIntervalMs)
    assertEquals(
      listOf(
        StreamInterest(Interest.Segments(setOf("a", "b")), 500),
        StreamInterest(Interest.Segments(setOf("c")), 1_000),
      ),
      subscription.interests,
    )
  }

  @Test
  fun subscriptionOf_followsEachInterestOnceAtItsShortestInterval() {
    val torrents = List(StreamEvents.MAX_TORRENTS + 2) {
      StreamInterest(TorrentInterest("t$it"), 2_000)
    }
    val subscription = subscriptionOf(
      listOf(
        StreamInterest(Interest.Stats, 3_000),
        StreamInterest(Interest.Stats, 800),
        StreamInterest(Interest.Connections(36), 500),
        StreamInterest(Interest.Connections(512), 2_000),
        StreamInterest(TorrentInterest("t0"), 1_000),
      ) + torrents,
      progress = null,
    )

    assertNull(subscription.progressIntervalMs)
    assertEquals(
      listOf(
        StreamInterest(Interest.Stats, 1_000),
        // The largest limit, as often as the most frequent caller asks.
        StreamInterest(Interest.Connections(512), 500),
        StreamInterest(TorrentInterest("t0"), 1_000),
      ) + torrents.drop(1).take(StreamEvents.MAX_TORRENTS - 1),
      subscription.interests,
    )
  }

  @Test
  fun subscriptionOf_capsTaskIds() {
    val ids = List(StreamEvents.MAX_TASK_IDS + 10) { "t$it" }.toSet()
    val subscription = subscriptionOf(
      listOf(StreamInterest(Interest.Segments(ids), 1_000)),
      progress = null,
    )

    val segments = subscription.interests.single().interest as Interest.Segments
    assertEquals(StreamEvents.MAX_TASK_IDS, segments.taskIds.size)
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
