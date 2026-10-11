package com.linroid.ketch.remote

import com.linroid.ketch.api.Interest
import com.linroid.ketch.endpoints.stream.StreamEvents
import com.linroid.ketch.endpoints.stream.StreamInterest
import com.linroid.ketch.endpoints.stream.StreamSegments
import com.linroid.ketch.torrent.api.TorrentInterest
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.seconds

class StreamInterestsTest {
  @Test
  fun subscriptionOf_mergesSegmentsByStep_eachTaskAtItsShortest() {
    val subscription = subscriptionOf(
      listOf(
        Interest.Segments(setOf("a", "b")) to 400L,
        Interest.Segments(setOf("b", "c")) to 1_000L,
        Interest.Segments(setOf("c")) to 900L,
      ),
      ids = emptyMap(),
      progress = 1.seconds,
    )

    // 400 ms rounds up to 500 ms, 900 ms to 1 s.
    assertEquals(1_000, subscription.progressIntervalMs)
    assertEquals(
      listOf(StreamSegments(setOf("a", "b"), 500), StreamSegments(setOf("c"), 1_000)),
      subscription.segments,
    )
    assertEquals(emptyList(), subscription.interests)
  }

  @Test
  fun subscriptionOf_followsEachInterestOnceAtItsShortestInterval() {
    val torrents = List(StreamEvents.MAX_INTERESTS) { TorrentInterest("t$it") }
    val held = listOf(
      Interest.Stats to 3_000L,
      Interest.Stats to 800L,
      Interest.Connections(36) to 500L,
      Interest.Connections(512) to 2_000L,
    ) + torrents.map { it to 2_000L }
    val ids = held.map { it.first }.distinct().withIndex().associate { (index, it) ->
      it to index + 1L
    }

    val subscription = subscriptionOf(held, ids, progress = null)

    assertNull(subscription.progressIntervalMs)
    assertEquals(
      listOf(
        StreamInterest(1, Interest.Stats, 1_000),
        // Each limit is its own interest.
        StreamInterest(2, Interest.Connections(36), 500),
        StreamInterest(3, Interest.Connections(512), 2_000),
      ) + torrents.take(StreamEvents.MAX_INTERESTS - 3).mapIndexed { index, interest ->
        StreamInterest(index + 4L, interest, 2_000)
      },
      subscription.interests,
    )
  }

  @Test
  fun subscriptionOf_capsTaskIds() {
    val ids = List(StreamEvents.MAX_TASK_IDS + 10) { "t$it" }.toSet()
    val subscription = subscriptionOf(
      listOf(Interest.Segments(ids) to 1_000L),
      ids = emptyMap(),
      progress = null,
    )

    assertEquals(StreamEvents.MAX_TASK_IDS, subscription.segments.single().taskIds.size)
  }

  @Test
  fun holding_sharesAnIdBetweenEqualInterests_andNeverGivesItToAnother() = runTest {
    val interests = StreamInterests(initialProgress = null)
    val released = CompletableDeferred<Unit>()
    val ids = mutableListOf<Long>()
    val holders = listOf(1.seconds, 2.seconds).map { interval ->
      launch {
        interests.holding(Interest.Stats, interval) { id ->
          ids += id
          released.await()
        }
      }
    }
    runCurrent()

    assertEquals(1, ids.toSet().size)
    val shared = ids.first()
    assertEquals(
      listOf(StreamInterest(shared, Interest.Stats, 1_000)),
      interests.subscriptions.first().interests,
    )

    released.complete(Unit)
    holders.forEach { it.join() }
    assertEquals(emptyList(), interests.subscriptions.first().interests)
    val again = interests.holding(Interest.Stats, 1.seconds) { it }
    assertNotEquals(shared, again)
  }
}
