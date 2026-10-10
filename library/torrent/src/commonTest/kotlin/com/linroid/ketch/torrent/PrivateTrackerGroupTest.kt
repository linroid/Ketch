package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import okio.IOException
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A private hybrid's two topics announcing to one tracker at a time (BEP 27). */
class PrivateTrackerGroupTest {
  private val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5), hybrid = true,
    privateTorrent = true)
  private val v2 = TrackerTopic.V2(fixture.document.info.hash)
  private val v1 = TrackerTopic.V1(checkNotNull(fixture.document.identity.v1))
  private val verified = BooleanArray(fixture.layout.pieceCount.toInt())
  private var now = 0L
  private var resets = 0

  /** Trackers that answer each topic every [intervals] seconds, unless [refuses] says not. */
  private inner class Trackers(
    private val intervals: Map<TrackerTopic, Long> = mapOf(v2 to 60L, v1 to 3600L),
    var refuses: (url: String, topic: TrackerTopic) -> Boolean = { _, _ -> false },
  ) {
    val announces = mutableListOf<String>()

    fun announce(url: String, request: TrackerAnnounce): TrackerResponse {
      val topic = if (request.topic == v1) "v1" else "v2"
      announces += "$url $topic ${request.event}"
      if (refuses(url, request.topic)) throw IOException("Tracker unavailable")
      return TrackerResponse(emptyList(), intervals.getValue(request.topic))
    }

    /** The announces since the last call, in any order. */
    fun drain(): Set<String> = announces.toSet().also { announces.clear() }
  }

  private fun group(trackers: Trackers, urls: List<String>) = PrivateTrackerGroup(
    listOf(v2, v1), urls, { topic, url ->
      TrackerDiscovery(fixture.document, fixture.layout, ByteArray(20), { 6881 },
        TrackerTiers(listOf(listOf(url))) { tracker, request, _ ->
          trackers.announce(tracker, request)
        }, nowMs = { now }, topic = topic)
    }, onSwitch = { resets++ }, nowMs = { now })

  private suspend fun PrivateTrackerGroup.poll() = poll(verified, 0, 0).map { it.first }.toSet()

  @Test
  fun topicsMoveTogetherAndStartAtOnceAtTheNextTracker() = runTest {
    val trackers = Trackers()
    val group = group(trackers, listOf("a", "b"))
    assertEquals(setOf(v2, v1), group.poll())
    assertEquals(setOf("a v2 STARTED", "a v1 STARTED"), trackers.drain())
    // a goes down; only the v2 topic is due, and its failure moves both.
    trackers.refuses = { url, _ -> url == "a" }
    now = 60_000
    assertEquals(emptySet(), group.poll())
    assertEquals(1, resets)
    assertEquals("b", group.current)
    assertEquals(setOf("a v2 NONE", "a v2 STOPPED", "a v1 STOPPED"), trackers.drain())
    // The v1 topic starts at b at once, long before its own interval at a would have ended.
    assertEquals(setOf(v2, v1), group.poll())
    assertEquals(setOf("b v2 STARTED", "b v1 STARTED"), trackers.drain())
    assertEquals(1, resets)
  }

  @Test
  fun answersOfTheOldTrackerAreDroppedWhenTheGroupMoves() = runTest {
    // Both topics come due together, as when a tracker gives both hashes one interval.
    val trackers = Trackers(intervals = mapOf(v2 to 60L, v1 to 60L))
    val group = group(trackers, listOf("a", "b"))
    assertEquals(setOf(v2, v1), group.poll())
    trackers.drain()
    // a still answers the v1 topic, but no longer the v2 one it answered before.
    trackers.refuses = { url, topic -> url == "a" && topic == v2 }
    now = 60_000
    // The move dropped a's peers: its v1 answer must not bring them back (BEP 27).
    assertEquals(emptySet(), group.poll())
    assertEquals(1, resets)
    assertEquals("b", group.current)
    assertEquals(setOf("a v2 NONE", "a v1 NONE", "a v2 STOPPED", "a v1 STOPPED"),
      trackers.drain())
    assertEquals(setOf(v2, v1), group.poll())
    assertEquals(setOf("b v2 STARTED", "b v1 STARTED"), trackers.drain())
  }

  @Test
  fun trackerThatKnowsOneHashKeepsBothTopics() = runTest {
    for (urls in listOf(listOf("a", "b"), listOf("b", "a"))) {
      // Each tracker knows one hash only: the group never splits the topics between them.
      val trackers = Trackers(refuses = { url, topic ->
        url == "a" && topic == v1 || url == "b" && topic == v2
      })
      val group = group(trackers, urls)
      repeat(4) {
        group.poll()
        now += 30_000
      }
      val used = trackers.announces.map { it.substringBefore(' ') }.toSet()
      assertEquals(setOf(urls.first()), used)
      assertEquals(urls.first(), group.current)
      assertEquals(0, resets)
      now = 0
    }
  }

  @Test
  fun silentTrackersBackOffAfterAWholeRound() = runTest {
    val trackers = Trackers(refuses = { _, _ -> true })
    val group = group(trackers, listOf("a", "b"))
    assertEquals(emptySet(), group.poll())
    assertEquals("b", group.current)
    assertEquals(emptySet(), group.poll())
    assertEquals(setOf("a v2 STARTED", "a v1 STARTED", "b v2 STARTED", "b v1 STARTED"),
      trackers.drain())
    // Neither answered: nobody's peers were dropped, and the next round waits.
    assertEquals(0, resets)
    group.poll()
    assertTrue(trackers.announces.isEmpty())
    now = 15_000
    group.poll()
    assertEquals(setOf("a v2 STARTED", "a v1 STARTED"), trackers.drain())
  }

  @Test
  fun stopTellsTheCurrentTracker() = runTest {
    val trackers = Trackers()
    val group = group(trackers, listOf("a", "b"))
    group.poll()
    trackers.drain()
    group.stop(verified, 0, 0)
    assertEquals(setOf("a v2 STOPPED", "a v1 STOPPED"), trackers.drain())
  }

  @Test
  fun orderKeepsTiersAndNamesEachTrackerOnce() {
    val order = PrivateTrackerGroup.order(listOf(listOf("a", "b", "a"), listOf("c", "b")),
      Random(7))
    assertEquals(setOf("a", "b"), order.take(2).toSet())
    assertEquals(listOf("c"), order.drop(2))
  }
}
