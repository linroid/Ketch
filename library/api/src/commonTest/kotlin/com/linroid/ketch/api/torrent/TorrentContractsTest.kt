package com.linroid.ketch.api.torrent

import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TorrentContractsTest {
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
}
