package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
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
    val discovery = TrackerDiscovery(metadata, ByteArray(20), { 6881 }, tiers, nowMs = { now })
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
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), { 6881 }, tiers,
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
    val discovery = TrackerDiscovery(document, layout, ByteArray(20), { 6881 }, tiers,
      nowMs = { now })
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
      ByteArray(20), { 6881 }, tiers)
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
      ByteArray(20), { 6881 }, tiers, onPrivateTrackerChanged = { drops++ }, nowMs = { now })
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
        ByteArray(20), { 6881 }, tiers)
    }
    val discovery = TrackerDiscovery(document, TorrentContentLayout.from(document.info),
      ByteArray(20), { 6881 }, tiers)
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
        ByteArray(20), { 6881 }, tiers, nowMs = { now })
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
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), { 6881 }, tiers, nowMs = { now })
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
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), { 6881 }, tiers, nowMs = { now })
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
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), { 6881 }, tiers, nowMs = { now })
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
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), { 6881 }, tiers, nowMs = { now })
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
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), { 6881 }, tiers, nowMs = { 0 })
    val bits = booleanArrayOf(false, false)
    assertFailsWith<CancellationException> { discovery.poll(bits, 0, 0) }
    discovery.poll(bits, 0, 0)
    assertEquals(2, calls)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun privateFailoverWaitsForOldPeersBeforeContactingReplacement() = runTest {
    var now = 0L
    var online = true
    var contactedReplacement = false
    val closing = CompletableDeferred<Unit>()
    val closed = CompletableDeferred<Unit>()
    val tiers = TrackerTiers(listOf(listOf("a"), listOf("b"))) { url, _, _ ->
      if (url == "a" && !online) error("Offline")
      if (url == "b") contactedReplacement = true
      TrackerResponse(emptyList(), 1)
    }
    val document = v2Document()
    val discovery = TrackerDiscovery(document, TorrentContentLayout.from(document.info),
      ByteArray(20), { 6881 }, tiers, onPrivateTrackerChanged = {
        closing.complete(Unit)
        closed.await()
      }, nowMs = { now })
    val bits = booleanArrayOf(false, false)
    discovery.poll(bits, 0, 0)
    online = false
    now = 1000
    val switching = async { discovery.poll(bits, 0, 0) }
    closing.await()
    runCurrent()
    assertEquals(false, contactedReplacement)
    closed.complete(Unit)
    assertEquals("b", switching.await()?.source)
    assertEquals(true, contactedReplacement)
  }

  @Test
  fun privateCleanupFailurePreventsReplacementAnnounce() = runTest {
    var now = 0L
    var online = true
    var replacementCalls = 0
    val tiers = TrackerTiers(listOf(listOf("a"), listOf("b"))) { url, _, _ ->
      if (url == "a" && !online) error("Offline")
      if (url == "b") replacementCalls++
      TrackerResponse(emptyList(), 1)
    }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), { 6881 }, tiers,
      onPrivateTrackerChanged = { error("Cleanup failed") }, nowMs = { now })
    val bits = booleanArrayOf(false, false)
    discovery.poll(bits, 0, 0)
    online = false
    now = 1000
    val error = assertFailsWith<IllegalStateException> { discovery.poll(bits, 0, 0) }
    assertEquals("Cleanup failed", error.message)
    assertEquals(0, replacementCalls)
  }

  @Test
  fun privateFailoverClosesOldPeersOnceAcrossFailedCandidates() = runTest {
    var now = 0L
    var firstOnline = true
    var cleanups = 0
    val tiers = TrackerTiers(listOf(listOf("a"), listOf("b"), listOf("c"))) { url, _, _ ->
      if ((url == "a" && !firstOnline) || url == "b") error("Offline")
      TrackerResponse(emptyList(), 1)
    }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), { 6881 }, tiers,
      onPrivateTrackerChanged = { cleanups++ }, nowMs = { now })
    val bits = booleanArrayOf(false, false)
    discovery.poll(bits, 0, 0)
    firstOnline = false
    now = 1000
    assertEquals("c", discovery.poll(bits, 0, 0)?.source)
    assertEquals(1, cleanups)
    now = 2000
    assertEquals("c", discovery.poll(bits, 0, 0)?.source)
    assertEquals(1, cleanups)
  }

  @Test
  fun hybridV1TopicAnnouncesTheV1HashWithoutPaddingInLeft() = runTest {
    // Pieces of 32 KiB: a holds 40000 bytes and 25536 bytes of padding, then b holds 5.
    val hybrid = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5), hybrid = true)
    val v1 = TrackerTopic.V1(checkNotNull(hybrid.document.identity.v1))
    val requests = mutableListOf<TrackerAnnounce>()
    val discovery = TrackerDiscovery(hybrid.document, hybrid.layout, ByteArray(20), { 6881 },
      TrackerTiers(listOf(listOf("a"))) { _, request, _ ->
        requests += request
        TrackerResponse(emptyList(), 60)
      }, topic = v1)
    assertEquals(v1, discovery.topic)
    discovery.poll(booleanArrayOf(true, false, false), 0, 0)
    assertEquals(v1, requests.single().topic)
    // Left counts the files' bytes still missing, never the padding the v1 swarm hashes.
    assertEquals(7_232L + 5, requests.single().left)
    // A topic of another torrent is refused before anything is announced.
    assertFailsWith<IllegalArgumentException> {
      TrackerDiscovery(hybrid.document, hybrid.layout, ByteArray(20), { 6881 },
        TrackerTiers(listOf(listOf("a"))) { _, _, _ -> error("Unused") },
        topic = TrackerTopic.V1(InfoHash.fromBytes(ByteArray(20))))
    }
  }

  @Test
  fun pollReadsTheAdvertisedPortAtEachAnnounce() = runTest {
    val port = TorrentAdvertisedPort()
    port.setListen(6881)
    var now = 0L
    val requests = mutableListOf<TrackerAnnounce>()
    // Tiers bind to the topic they first announce, so each discovery gets its own.
    fun tiers() = TrackerTiers(listOf(listOf("a"))) { _, request, _ ->
      requests += request
      TrackerResponse(emptyList(), 60)
    }
    val document = v2Document()
    for (discovery in listOf(
      TrackerDiscovery(metadata(), ByteArray(20), port::current, tiers(), nowMs = { now }),
      TrackerDiscovery(document, TorrentContentLayout.from(document.info), ByteArray(20),
        port::current, tiers(), nowMs = { now }),
    )) {
      requests.clear()
      now = 0
      port.setListen(6881)
      val bits = booleanArrayOf(false, false)
      discovery.poll(bits, 0, 0)
      // The listener moved after the session started: later announces use the new port.
      port.setListen(51413)
      now = 60_000
      discovery.poll(bits, 0, 0)
      discovery.poll(bits, 0, 0, stopped = true)
      assertEquals(listOf(6881, 51413, 51413), requests.map { it.port })
      assertEquals(listOf(TrackerEvent.STARTED, TrackerEvent.NONE, TrackerEvent.STOPPED),
        requests.map { it.event })
    }
  }

  @Test
  fun poll_selectionBecomesComplete_announcesCompletedOnce() = runTest {
    var now = 0L
    var selectsAll = false
    val requests = mutableListOf<TrackerAnnounce>()
    val tiers = TrackerTiers(listOf(listOf("a"))) { _, request, _ ->
      requests += request
      TrackerResponse(emptyList(), 60)
    }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), { 6881 }, tiers,
      nowMs = { now }, announceCompletion = { selectsAll })
    discovery.poll(booleanArrayOf(true, false), 4, 0)
    assertEquals(TrackerEvent.STARTED, requests.last().event)
    now = 60_000
    // Every piece of a partial selection: still not complete for the tracker.
    discovery.poll(booleanArrayOf(true, true), 7, 0)
    assertEquals(TrackerEvent.NONE, requests.last().event)
    // The selection grew to the whole torrent, which it holds: completed goes out at once.
    selectsAll = true
    discovery.poll(booleanArrayOf(true, true), 7, 0)
    assertEquals(TrackerEvent.COMPLETED, requests.last().event)
    now = 120_000
    discovery.poll(booleanArrayOf(true, true), 7, 0)
    assertEquals(TrackerEvent.NONE, requests.last().event)
    assertEquals(1, requests.count { it.event == TrackerEvent.COMPLETED })
  }

  @Test
  fun poll_partialSelectionHoldingEveryPiece_sendsNoCompleted() = runTest {
    var now = 0L
    val requests = mutableListOf<TrackerAnnounce>()
    val tiers = TrackerTiers(listOf(listOf("a"))) { _, request, _ ->
      requests += request
      TrackerResponse(emptyList(), 60)
    }
    val discovery = TrackerDiscovery(metadata(), ByteArray(20), { 6881 }, tiers,
      nowMs = { now }, announceCompletion = { false })
    discovery.poll(booleanArrayOf(false, false), 0, 0)
    for (round in 1..3) {
      now = round * 60_000L
      discovery.poll(booleanArrayOf(true, true), 7, 0)
      assertEquals(0L, requests.last().left)
    }
    discovery.poll(booleanArrayOf(true, true), 7, 0, stopped = true)
    assertEquals(listOf(TrackerEvent.STARTED, TrackerEvent.NONE, TrackerEvent.NONE,
      TrackerEvent.NONE, TrackerEvent.STOPPED), requests.map { it.event })
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
