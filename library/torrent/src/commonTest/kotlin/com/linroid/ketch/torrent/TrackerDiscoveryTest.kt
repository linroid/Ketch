package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class TrackerDiscoveryTest {
  @Test
  fun selectionCompletion_keepsWholeTorrentLeftAndHonorsInterval() = runTest {
    val metadata = metadata()
    var now = 0L
    val requests = mutableListOf<TrackerAnnounce>()
    val tiers = TrackerTiers(listOf(listOf("a"))) { _, request, _ ->
      requests += request
      TrackerResponse(emptyList(), 60)
    }
    val discovery = TrackerDiscovery(metadata, ByteArray(20), 6881, tiers, nowMs = { now })
    val verified = booleanArrayOf(true, false)
    assertNotNull(discovery.poll(verified, 20, 0))
    assertEquals(TrackerEvent.STARTED, requests.last().event)
    assertEquals(3L, requests.last().left)
    assertNull(discovery.poll(verified, 20, 0))
    now = 60_000
    discovery.poll(verified, 20, 0)
    assertEquals(TrackerEvent.NONE, requests.last().event)
    discovery.poll(booleanArrayOf(true, true), 23, 0)
    assertEquals(TrackerEvent.COMPLETED, requests.last().event)
    assertEquals(0L, requests.last().left)
    discovery.poll(booleanArrayOf(true, true), 23, 0, stopped = true)
    assertEquals(TrackerEvent.STOPPED, requests.last().event)
  }

  @Test
  fun privateTrackerSwitch_dropsPriorPeersBeforeReturningNewPeers() = runTest {
    var firstOnline = true
    val tiers = TrackerTiers(listOf(listOf("a"), listOf("b"))) { url, _, _ ->
      if (url == "a" && !firstOnline) error("offline")
      TrackerResponse(listOf(PeerEndpoint("127.0.0.1", 6881)), 1)
    }
    var drops = 0
    var now = 0L
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), 6881, tiers,
      onPrivateTrackerChanged = { drops++ }, nowMs = { now })
    discovery.poll(booleanArrayOf(false, false), 0, 0)
    assertEquals(0, drops)
    firstOnline = false
    now = 1000
    assertEquals("b", discovery.poll(booleanArrayOf(false, false), 0, 0)?.source)
    assertEquals(1, drops)
  }

  @Test
  fun v2TrackerLeftCountsUnselectedPayloadWithoutAlignmentGaps() = runTest {
    val document = v2Document()
    val layout = TorrentContentLayout.from(document.info)
    val requests = mutableListOf<TrackerAnnounce>()
    var now = 0L
    val tiers = TrackerTiers(listOf(listOf("a"))) { _, request, _ ->
      requests += request
      TrackerResponse(emptyList(), 60)
    }
    val discovery = TrackerDiscovery(document, layout, ByteArray(20), 6881, tiers, nowMs = { now })
    discovery.poll(booleanArrayOf(true, false), 1000, 0)
    assertEquals(document.info.hash, assertIs<TrackerTopic.V2>(requests.last().topic).hash)
    assertEquals(5L, requests.last().left)
    assertEquals(TrackerEvent.STARTED, requests.last().event)
    assertNull(discovery.poll(booleanArrayOf(true, false), 2000, 0))
    now = 60_000
    discovery.poll(booleanArrayOf(false, false), 2000, 0)
    assertEquals(8L, requests.last().left)
    discovery.poll(booleanArrayOf(true, true), 2003, 0)
    assertEquals(TrackerEvent.COMPLETED, requests.last().event)
    assertEquals(0L, requests.last().left)
    discovery.poll(booleanArrayOf(true, true), 2003, 0, stopped = true)
    assertEquals(TrackerEvent.STOPPED, requests.last().event)
  }

  @Test
  fun alreadyCompleteV2StartDoesNotEmitACompletionEvent() = runTest {
    val document = v2Document()
    val requests = mutableListOf<TrackerAnnounce>()
    val tiers = TrackerTiers(listOf(listOf("a"))) { _, request, _ ->
      requests += request
      TrackerResponse(emptyList(), 60)
    }
    val discovery = TrackerDiscovery(document, TorrentContentLayout.from(document.info),
      ByteArray(20), 6881, tiers)
    assertNull(discovery.poll(booleanArrayOf(true, true), 0, 0, stopped = true))
    discovery.poll(booleanArrayOf(true, true), 0, 0)
    assertEquals(TrackerEvent.STARTED, requests.single().event)
    assertNull(discovery.poll(booleanArrayOf(true, true), 0, 0))
  }

  @Test
  fun v2PrivateFailoverDropsThePreviousTrackerPeersBeforeReturning() = runTest {
    val document = v2Document()
    var online = true
    var now = 0L
    var drops = 0
    val tiers = TrackerTiers(listOf(listOf("a"), listOf("b"))) { url, _, _ ->
      if (url == "a" && !online) error("Offline")
      TrackerResponse(listOf(PeerEndpoint("127.0.0.1", 1)), 1)
    }
    val discovery = TrackerDiscovery(document, TorrentContentLayout.from(document.info),
      ByteArray(20), 6881, tiers, onPrivateTrackerChanged = { drops++ }, nowMs = { now })
    discovery.poll(booleanArrayOf(false, false), 0, 0)
    online = false
    now = 1000
    assertEquals("b", discovery.poll(booleanArrayOf(false, false), 0, 0)?.source)
    assertEquals(1, drops)
  }

  @Test
  fun v2DiscoveryRejectsWrongLayoutAndVerificationLengthBeforeAnnouncing() = runTest {
    val document = v2Document()
    var calls = 0
    val tiers = TrackerTiers(listOf(listOf("a"))) { _, _, _ ->
      calls++
      TrackerResponse(emptyList(), 60)
    }
    assertFailsWith<IllegalArgumentException> {
      TrackerDiscovery(document, TorrentContentLayout.from(v2Document("other").info),
        ByteArray(20), 6881, tiers)
    }
    val discovery = TrackerDiscovery(document, TorrentContentLayout.from(document.info),
      ByteArray(20), 6881, tiers)
    assertFailsWith<IllegalArgumentException> { discovery.poll(booleanArrayOf(false), 0, 0) }
    assertEquals(0, calls)
  }

  @Test
  fun manualReannounceRespectsTrackerMinimumAndLocalFloor() = runTest {
    for (minimum in listOf(10L, 300L)) {
      val requests = mutableListOf<TrackerAnnounce>()
      var now = 0L
      val tiers = TrackerTiers(listOf(listOf("a"))) { _, request, _ ->
        requests += request
        TrackerResponse(emptyList(), 3600, minimumIntervalSeconds = minimum)
      }
      val document = v2Document()
      val discovery = TrackerDiscovery(document, TorrentContentLayout.from(document.info),
        ByteArray(20), 6881, tiers, nowMs = { now })
      val bits = booleanArrayOf(false, false)
      discovery.poll(bits, 0, 0)
      now = maxOf(60L, minimum) * 1000 - 1
      assertNull(discovery.poll(bits, 0, 0, manual = true))
      now++
      assertNull(discovery.poll(bits, 0, 0))
      discovery.poll(bits, 0, 0, manual = true)
      assertEquals(listOf(TrackerEvent.STARTED, TrackerEvent.NONE), requests.map { it.event })
      assertNull(discovery.poll(bits, 0, 0, manual = true))
      now += 3_600_000
      discovery.poll(bits, 0, 0)
      assertEquals(3, requests.size)
    }
  }

  @Test
  fun shortAutomaticIntervalsDoNotDisableTheManualRateLimit() = runTest {
    var now = 0L
    var calls = 0
    val tiers = TrackerTiers(listOf(listOf("a"))) { _, _, _ ->
      calls++
      TrackerResponse(emptyList(), 1, minimumIntervalSeconds = 1)
    }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), 6881, tiers, nowMs = { now })
    val bits = booleanArrayOf(false, false)
    discovery.poll(bits, 0, 0)
    now = 1000
    assertNull(discovery.poll(bits, 0, 0, manual = true))
    discovery.poll(bits, 0, 0)
    assertEquals(2, calls)
  }

  @Test
  fun missingMinimumUsesRegularIntervalForManualAnnounces() = runTest {
    var now = 0L
    var calls = 0
    val tiers = TrackerTiers(listOf(listOf("a"))) { _, _, _ ->
      calls++
      TrackerResponse(emptyList(), 600)
    }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), 6881, tiers, nowMs = { now })
    val bits = booleanArrayOf(false, false)
    discovery.poll(bits, 0, 0)
    now = 599_999
    assertNull(discovery.poll(bits, 0, 0, manual = true))
    now++
    discovery.poll(bits, 0, 0, manual = true)
    assertEquals(2, calls)
  }

  @Test
  fun failedAnnouncesBackOffExponentiallyAndSuccessResetsTheDelay() = runTest {
    var now = 0L
    var failing = true
    var calls = 0
    val tiers = TrackerTiers(listOf(listOf("a"))) { _, _, _ ->
      calls++
      if (failing) error("Offline")
      TrackerResponse(emptyList(), 1)
    }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), 6881, tiers, nowMs = { now })
    val bits = booleanArrayOf(false, false)
    for (delay in listOf(15, 30, 60, 120, 240, 480, 900, 900)) {
      assertFailsWith<IllegalStateException> { discovery.poll(bits, 0, 0, manual = true) }
      val before = calls
      now += delay * 1000 - 1
      assertNull(discovery.poll(bits, 0, 0, manual = true))
      assertEquals(before, calls)
      now++
    }
    failing = false
    discovery.poll(bits, 0, 0)
    failing = true
    now += 1000
    assertFailsWith<IllegalStateException> { discovery.poll(bits, 0, 0) }
    now += 14_999
    assertNull(discovery.poll(bits, 0, 0))
    now++
    failing = false
    discovery.poll(bits, 0, 0)
    assertEquals(11, calls)
  }

  @Test
  fun stopBypassesFailureBackoffAndCompletionBypassesManualThrottle() = runTest {
    var now = 0L
    var failing = false
    val events = mutableListOf<TrackerEvent>()
    val tiers = TrackerTiers(listOf(listOf("a"))) { _, request, _ ->
      if (failing) error("Offline")
      events += request.event
      TrackerResponse(emptyList(), 3600, minimumIntervalSeconds = 60)
    }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), 6881, tiers, nowMs = { now })
    discovery.poll(booleanArrayOf(false, false), 0, 0)
    discovery.poll(booleanArrayOf(true, true), 7, 0)
    assertEquals(listOf(TrackerEvent.STARTED, TrackerEvent.COMPLETED), events)
    now = 60_000
    failing = true
    assertFailsWith<IllegalStateException> {
      discovery.poll(booleanArrayOf(true, true), 7, 0, manual = true)
    }
    failing = false
    discovery.poll(booleanArrayOf(true, true), 7, 0, stopped = true)
    assertEquals(TrackerEvent.STOPPED, events.last())
  }

  @Test
  fun cancellationDoesNotBecomeTrackerFailureBackoff() = runTest {
    var calls = 0
    val tiers = TrackerTiers(listOf(listOf("a"))) { _, _, _ ->
      if (calls++ == 0) throw CancellationException("Canceled announce")
      TrackerResponse(emptyList(), 60)
    }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), 6881, tiers, nowMs = { 0 })
    val bits = booleanArrayOf(false, false)
    assertFailsWith<CancellationException> { discovery.poll(bits, 0, 0) }
    discovery.poll(bits, 0, 0)
    assertEquals(2, calls)
  }

  private fun v2Document(name: String = "test"): TorrentV2Document =
    TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
      "name" to name, "private" to 1L, "meta version" to 2L, "piece length" to 16_384L,
      "file tree" to mapOf("a" to mapOf("" to mapOf("length" to 3L,
        "pieces root" to sha256Digest(byteArrayOf(1, 2, 3)))),
        "b" to mapOf("" to mapOf("length" to 5L,
          "pieces root" to sha256Digest(ByteArray(5)))))
    ), "piece layers" to emptyMap<String, Any>())))

  private fun metadata(): TorrentMetadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
    "info" to mapOf("name" to "test", "length" to 7L, "piece length" to 4L,
      "pieces" to (sha1Digest(byteArrayOf(1, 2, 3, 4)) + sha1Digest(byteArrayOf(5, 6, 7))),
      "private" to 1L)
  )))
}
