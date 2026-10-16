package com.linroid.ketch.torrent.api

import com.linroid.ketch.api.FileOrder
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TorrentContractsTest {
  @Test
  fun reachability_roundTripsByWireName_andReadsUnknownStatusesAsUnknown() {
    val blocked = TorrentReachability(TorrentReachabilityStatus.BLOCKED, port = 6881,
      forwardedLayers = 1, incomingPeers = 0, holePunching = true)
    val text = Json.encodeToString(blocked)
    assertTrue(text.contains("\"status\":\"blocked\""), text)
    assertEquals(blocked, Json.decodeFromString<TorrentReachability>(text))
    assertEquals(TorrentReachabilityStatus.NOT_FORWARDED,
      Json.decodeFromString<TorrentReachability>("""{"status":"not-forwarded"}""").status)
    assertEquals(TorrentReachabilityStatus.UNKNOWN,
      Json.decodeFromString<TorrentReachability>("""{"status":"relayed"}""").status)
  }

  @Test
  fun capabilities_unknownNamesSurviveButUnknownMajorDoesNotEnableCommands() {
    val capabilities = Json.decodeFromString<TorrentCapabilities>(
      """{"names":["v2","future-feature"]}"""
    )
    assertTrue(capabilities.supports(TorrentCapability.V2))
    assertFalse(capabilities.supports(TorrentCapability.HYBRID))
    assertTrue("future-feature" in capabilities.names)
    assertFalse(capabilities.copy(protocolMajor = 2).supports(TorrentCapability.V2))
  }

  @Test
  fun snapshotOrdering_oldHttpResponseCannotReplaceNewerEvent() {
    val current = TorrentRevision("catalog-a", 10)
    assertEquals(TorrentRevisionDecision.IGNORE,
      current.compareIncoming(TorrentRevision("catalog-a", 9)))
    assertEquals(TorrentRevisionDecision.IGNORE, current.compareIncoming(current))
    assertEquals(TorrentRevisionDecision.APPLY,
      current.compareIncoming(TorrentRevision("catalog-a", 12)))
  }

  @Test
  fun deltaOrdering_gapsAndBackendRestartsRequireResync() {
    val current = TorrentRevision("catalog-a", 10)
    val next = TorrentRevision("catalog-a", 12)
    assertEquals(TorrentRevisionDecision.APPLY, current.compareIncoming(next, current))
    assertEquals(TorrentRevisionDecision.RESYNC,
      current.compareIncoming(next, TorrentRevision("catalog-a", 11)))
    assertEquals(TorrentRevisionDecision.RESYNC,
      current.compareIncoming(TorrentRevision("catalog-b", 1)))
    assertEquals(TorrentRevisionDecision.RESYNC,
      current.compareIncoming(next, TorrentRevision("catalog-b", 10)))
  }

  @Test
  fun malformedWireValuesAreRejected() {
    assertFailsWith<IllegalArgumentException> {
      Json.decodeFromString<TorrentRevision>("""{"epoch":"a","sequence":-1}""")
    }
    assertFailsWith<IllegalArgumentException> { TorrentPageRequest(limit = 1001) }
    assertFailsWith<IllegalArgumentException> { TorrentPageRequest(cursor = "") }
    assertFailsWith<IllegalArgumentException> {
      TorrentCommandContext(" ", TorrentRevision("a", 0))
    }
    assertFailsWith<IllegalArgumentException> {
      TorrentCapabilities(names = setOf("x".repeat(65)))
    }
  }

  @Test
  fun completionRequiresVerifiedSelectionButDoesNotRequireWholeTorrent() {
    val counters = TorrentCounters(100, 40, 40, 50, 0, 10, 15, 0, 0, 0)
    val snapshot = TorrentSnapshot("task", TorrentRevision("a", 1), TorrentActivity.SEEDING,
      2, true, counters)
    assertTrue(snapshot.selectionComplete)
    assertFailsWith<IllegalArgumentException> {
      snapshot.copy(counters = counters.copy(selectedVerifiedBytes = 39))
    }
    assertFailsWith<IllegalArgumentException> { snapshot.copy(counters = null) }
    assertFailsWith<IllegalArgumentException> { counters.copy(wantedBytes = 101) }
    assertFailsWith<IllegalArgumentException> { counters.copy(selectedVerifiedBytes = -1) }
  }

  @Test
  fun snapshot_seeding_roundTripsAndIsNullFromOlderBackends() {
    val snapshot = TorrentSnapshot("task", TorrentRevision("a", 1), TorrentActivity.STOPPED,
      0, false, seeding = true)
    val encoded = Json.encodeToString(TorrentSnapshot.serializer(), snapshot)
    assertEquals(snapshot, Json.decodeFromString(TorrentSnapshot.serializer(), encoded))

    val older = Json.decodeFromString<TorrentSnapshot>(
      """{"taskId":"task","revision":{"epoch":"a","sequence":1},"activity":"SEEDING",""" +
        """"selectionGeneration":0,"selectionComplete":false}"""
    )
    assertNull(older.seeding)
    assertEquals(TorrentActivity.SEEDING, older.activity)
  }

  @Test
  fun counters_untrackedFields_areNull() {
    val counters = Json.decodeFromString<TorrentCounters>(
      """{"totalPayloadBytes":100,"wantedBytes":40,"selectedVerifiedBytes":10}"""
    )
    assertEquals(null, counters.receivedPayloadBytes)
    assertEquals(null, counters.uploadedPayloadBytes)
    assertEquals(null, counters.discardedPayloadBytes)
    assertEquals(null, counters.protocolBytes)
    assertEquals(null, counters.downloadBytesPerSecond)
    assertEquals(null, counters.uploadBytesPerSecond)
    assertEquals(null, counters.seedSeconds)
    assertFailsWith<IllegalArgumentException> { counters.copy(uploadedPayloadBytes = -1) }
  }

  @Test
  fun filePage_oversizedPage_isRejected() {
    val revision = TorrentRevision("a", 1)
    val entry = TorrentFileEntry("0", "a.mkv", 10, selected = true)
    TorrentFilePage("task", revision, 0, 1000, List(1000) { entry })
    assertFailsWith<IllegalArgumentException> {
      TorrentFilePage("task", revision, 0, 1001, List(1001) { entry })
    }
    assertFailsWith<IllegalArgumentException> {
      TorrentFilePage("task", revision, 0, 1, listOf(entry), nextCursor = "")
    }
    assertFailsWith<IllegalArgumentException> { TorrentFileEntry("", "a", 1, false) }
    assertFailsWith<IllegalArgumentException> { TorrentFileEntry("0", "a", -1, false) }
  }

  @Test
  fun commandError_fromWire_roundTrips() {
    TorrentCommandError.entries.forEach {
      assertEquals(it, TorrentCommandError.fromWire(it.wireName))
    }
    assertEquals(TorrentCommandError.CONFLICT, TorrentCommandError.fromWire("revision_conflict"))
    assertEquals(null, TorrentCommandError.fromWire("future_error"))
    assertEquals(null, TorrentCommandError.fromWire(null))
    TorrentFileOrder.entries.forEach {
      assertEquals(it, TorrentFileOrder.fromWire(it.wireName))
    }
    assertEquals(null, TorrentFileOrder.fromWire("kind"))
  }

  @Test
  fun fileOrder_everyGenericOrderHasATorrentOrder() {
    FileOrder.entries.forEach { assertEquals(it, TorrentFileOrder.of(it).order) }
  }

  @Test
  fun controller_defaultCommands_failAsUnsupported() = runTest {
    val controller = object : TorrentController {
      override suspend fun capabilities() = TorrentCapabilities()
      override suspend fun snapshot(taskId: String): TorrentSnapshot? = null
    }
    val context = TorrentCommandContext("key", TorrentRevision("a", 0))
    val errors = listOf(
      assertFailsWith<TorrentCommandException> { controller.files("task") },
      assertFailsWith<TorrentCommandException> { controller.select("task", setOf("0"), context) },
      assertFailsWith<TorrentCommandException> { controller.setSeeding("task", true, context) }
    )
    assertTrue(errors.all { it.error == TorrentCommandError.UNSUPPORTED })
  }
}
