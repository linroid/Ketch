package com.linroid.ketch.app.ui.inspector

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.api.TorrentStallReason
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** What the inspector says of a stalled torrent, and what Skip file leaves it to download. */
class StallNoticeTest {
  private val missing = TorrentStall(TorrentStallReason.MISSING_PIECES, seconds = 120,
    missingPieces = 2, missingFileIds = listOf("2"), peers = 3, unchokingPeers = 1)

  private fun request(vararg selected: String) = DownloadRequest(
    url = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567",
    selectedFileIds = selected.toSet(),
    resolvedSource = ResolvedSource(
      url = "magnet:", sourceType = "torrent", totalBytes = 30, supportsResume = true,
      suggestedFileName = null, maxSegments = 1,
      files = (0..2).map { SourceFile(it.toString(), "f$it", 10) },
    ),
  )

  @Test
  fun skip_leavesOutTheFilesNoSourceHas() {
    assertEquals(setOf("0", "1"), filesAfterSkipping(request(), missing))
    assertEquals(setOf("1"), filesAfterSkipping(request("1", "2"), missing))
  }

  @Test
  fun skip_isOfferedOnlyWhenItLeavesSomethingAndRemovesSomething() {
    // The missing file is the only one chosen.
    assertNull(filesAfterSkipping(request("2"), missing))
    // The missing file is not chosen, so skipping changes nothing.
    assertNull(filesAfterSkipping(request("0"), missing))
    // Other reasons name no files.
    assertNull(filesAfterSkipping(request(),
      missing.copy(reason = TorrentStallReason.NO_PEERS, missingFileIds = emptyList())))
  }

  @Test
  fun message_saysWhy() = runTest {
    assertEquals("No peer or web seed has 2 pieces it needs", stallMessage(missing).load())
    assertEquals("No peer or web seed has 1 piece it needs",
      stallMessage(missing.copy(missingPieces = 1)).load())
    assertEquals("Peers connected, but none is sharing yet",
      stallMessage(missing.copy(reason = TorrentStallReason.CHOKED)).load())
    assertEquals("No peers for 2m 0s · still looking",
      stallMessage(missing.copy(reason = TorrentStallReason.NO_PEERS)).load())
  }
}
