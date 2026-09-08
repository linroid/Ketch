package com.linroid.ketch.torrent

import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
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

  private fun metadata(): TorrentMetadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf(
    "info" to mapOf("name" to "test", "length" to 7L, "piece length" to 4L,
      "pieces" to (sha1Digest(byteArrayOf(1, 2, 3, 4)) + sha1Digest(byteArrayOf(5, 6, 7))),
      "private" to 1L)
  )))
}
