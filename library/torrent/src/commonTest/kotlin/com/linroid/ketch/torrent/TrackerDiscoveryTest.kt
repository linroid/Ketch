package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
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
