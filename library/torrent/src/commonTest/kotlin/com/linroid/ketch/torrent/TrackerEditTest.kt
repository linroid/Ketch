package com.linroid.ketch.torrent

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TrackerEditTest {
  private val old = "https://old/announce?key=secret"
  private val next = "https://next/announce"
  private val bits = booleanArrayOf(false)
  private fun metadata() = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
    "name" to "file", "length" to 1L, "piece length" to 1L,
    "pieces" to sha1Digest(byteArrayOf(1)), "private" to 1L
  ))))

  @Test
  fun replacementStopsOldTrackerClosesPeersThenStartsFreshWithoutCachedIds() = runTest {
    val events = mutableListOf<String>()
    val tiers = TrackerTiers(listOf(listOf(old))) { url, request, id ->
      events += "${request.event}:$url"
      if (request.event == TrackerEvent.STARTED) assertNull(id)
      TrackerResponse(emptyList(), 3600, trackerId = byteArrayOf(7))
    }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), 6881, tiers,
      onPrivateTrackerChanged = { events += "close peers" })
    discovery.poll(bits, 0, 0)
    val previous = tiers.status()
    discovery.replaceTrackers(listOf(listOf(old)), bits, 0, 0)
    assertEquals(listOf("STARTED:$old", "STOPPED:$old", "close peers"), events)
    assertEquals(1L, tiers.status().single().configurationRevision)
    assertEquals(0L, previous.single().configurationRevision)
    assertEquals(0L, tiers.status().single().attempts)
    discovery.poll(bits, 0, 0)
    assertEquals("STARTED:$old", events.last())
  }

  @Test
  fun replacementSnapshotsInputBeforeOldStopSuspends() = runTest {
    val input = mutableListOf(listOf(next))
    val calls = mutableListOf<String>()
    val tiers = TrackerTiers(listOf(listOf(old))) { url, request, _ ->
      calls += url
      if (request.event == TrackerEvent.STOPPED) input.clear()
      TrackerResponse(emptyList(), 60)
    }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), 6881, tiers)
    discovery.poll(bits, 0, 0)
    discovery.replaceTrackers(input, bits, 0, 0)
    discovery.poll(bits, 0, 0)
    assertEquals(listOf(old, old, next), calls)
  }

  @Test
  fun localStopTimeoutStillAllowsReplacement() = runTest {
    val calls = mutableListOf<String>()
    val tiers = TrackerTiers(listOf(listOf(old))) { url, request, _ ->
      calls += url
      if (request.event == TrackerEvent.STOPPED) awaitCancellation()
      TrackerResponse(emptyList(), 60)
    }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), 6881, tiers)
    discovery.poll(bits, 0, 0)
    discovery.replaceTrackers(listOf(listOf(next)), bits, 0, 0, stopTimeoutMs = 5)
    discovery.poll(bits, 0, 0)
    assertEquals(listOf(old, old, next), calls)
    assertEquals(5L, testScheduler.currentTime)
  }

  @Test
  fun parentCancellationDoesNotInstallTheReplacement() = runTest {
    val tiers = TrackerTiers(listOf(listOf(old))) { _, request, _ ->
      if (request.event == TrackerEvent.STOPPED) awaitCancellation()
      TrackerResponse(emptyList(), 60)
    }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), 6881, tiers)
    discovery.poll(bits, 0, 0)
    assertFailsWith<TimeoutCancellationException> {
      withTimeout(2) {
        discovery.replaceTrackers(listOf(listOf(next)), bits, 0, 0, stopTimeoutMs = 10)
      }
    }
    assertEquals(0L, tiers.status().single().configurationRevision)
    assertEquals(TrackerStatus.Outcome.CANCELED, tiers.status().single().outcome)
  }

  @Test
  fun invalidConfigurationIsRejectedBeforeStoppingOrChangingOldTrackers() = runTest {
    var calls = 0
    val tiers = TrackerTiers(listOf(listOf(old))) { _, _, _ ->
      calls++
      TrackerResponse(emptyList(), 60)
    }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), 6881, tiers)
    discovery.poll(bits, 0, 0)
    for (invalid in listOf(listOf(listOf("file:///secret")), listOf(listOf("relative")),
      listOf(listOf("udp://user:password@host:80")), List(257) { listOf(next) },
      listOf(listOf("https://host/" + "x".repeat(8192))))) {
      assertFailsWith<IllegalArgumentException> { discovery.replaceTrackers(invalid, bits, 0, 0) }
    }
    assertEquals(1, calls)
    assertEquals(0L, tiers.status().single().configurationRevision)
  }

  @Test
  fun privateCleanupFailureKeepsTheOldConfiguration() = runTest {
    val calls = mutableListOf<String>()
    val tiers = TrackerTiers(listOf(listOf(old))) { url, _, _ ->
      calls += url
      TrackerResponse(emptyList(), 60)
    }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), 6881, tiers,
      onPrivateTrackerChanged = { error("Cleanup failed") })
    discovery.poll(bits, 0, 0)
    val error = assertFailsWith<IllegalStateException> {
      discovery.replaceTrackers(listOf(listOf(next)), bits, 0, 0)
    }
    assertEquals("Cleanup failed", error.message)
    assertEquals(0L, tiers.status().single().configurationRevision)
    discovery.poll(bits, 0, 0)
    assertEquals(listOf(old, old, old), calls)
  }

  @Test
  fun cancellationAtCleanupReturnDoesNotCommitReplacement() = runTest {
    val tiers = TrackerTiers(listOf(listOf(old))) { _, _, _ -> TrackerResponse(emptyList(), 60) }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), 6881, tiers,
      onPrivateTrackerChanged = { currentCoroutineContext().cancel() })
    discovery.poll(bits, 0, 0)
    val replacement = async { discovery.replaceTrackers(listOf(listOf(next)), bits, 0, 0) }
    assertFailsWith<CancellationException> { replacement.await() }
    assertEquals(0L, tiers.status().single().configurationRevision)
    assertEquals(2L, tiers.status().single().attempts)
  }

  @Test
  fun removingAllTrackersKeepsTheTopicBinding() = runTest {
    val tiers = TrackerTiers(listOf(listOf(old))) { _, _, _ -> TrackerResponse(emptyList(), 60) }
    val hash = metadata().infoHash
    val request = TrackerAnnounce(hash, ByteArray(20), 6881, 0, 1)
    tiers.announce(request)
    tiers.replace(TrackerConfiguration.prepare(emptyList()))
    assertEquals(emptyList(), tiers.status())
    tiers.replace(TrackerConfiguration.prepare(listOf(listOf(next))))
    assertFailsWith<IllegalArgumentException> {
      tiers.announce(request.copy(topic = TrackerTopic.V2(V2InfoHash.fromBytes(ByteArray(32)))))
    }
    assertEquals(0L, tiers.status().single().attempts)
  }
}
