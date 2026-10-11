package com.linroid.ketch.torrent

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TorrentPeerDiagnosticsTest {
  private fun attempt(stage: PeerStage, payload: Long = 0) =
    PeerAttempt(incoming = false).also {
      it.stage = stage
      it.payload = payload
    }

  private suspend fun timeout(): TimeoutCancellationException = try {
    withTimeout(1) { delay(1000) }
    error("No timeout")
  } catch (e: TimeoutCancellationException) {
    e
  }

  @Test
  fun classify_dialFailures_byCause() = runTest {
    val dialing = attempt(PeerStage.DIALING)
    assertEquals(PeerOutcome.CONNECT_TIMEOUT, classifyPeerOutcome(dialing, timeout()))
    assertEquals(PeerOutcome.REFUSED,
      classifyPeerOutcome(dialing, IOException("Connection refused")))
    assertEquals(PeerOutcome.UNREACHABLE,
      classifyPeerOutcome(dialing, IOException("x", IOException("Network is unreachable"))))
    assertEquals(PeerOutcome.CONNECT_FAILED, classifyPeerOutcome(dialing, IOException("other")))
  }

  @Test
  fun classify_handshakeFailures_byCause() = runTest {
    val handshaking = attempt(PeerStage.HANDSHAKING)
    assertEquals(PeerOutcome.HANDSHAKE_TIMEOUT, classifyPeerOutcome(handshaking, timeout()))
    assertEquals(PeerOutcome.HANDSHAKE_REJECTED,
      classifyPeerOutcome(handshaking, IllegalArgumentException("Peer belongs to another swarm")))
    assertEquals(PeerOutcome.HANDSHAKE_CLOSED,
      classifyPeerOutcome(handshaking, IOException("Connection reset by peer")))
  }

  @Test
  fun classify_connected_byPayload_andStoppedAttemptsAreNotRecorded() {
    assertEquals(PeerOutcome.CONNECTED,
      classifyPeerOutcome(attempt(PeerStage.CONNECTED), IOException("closed")))
    assertEquals(PeerOutcome.RECEIVED, classifyPeerOutcome(attempt(PeerStage.CONNECTED, 1), null))
    assertNull(classifyPeerOutcome(attempt(PeerStage.DIALING), null))
  }

  @Test
  fun summary_keepsEachEndpointsBestOutcome_andCountsFlags() {
    val reports = mutableListOf<PeerOutcomeReport>()
    val diagnostics = PeerDiagnostics(listener = { reports += it })
    val a = PeerEndpoint("10.0.0.1", 1)
    val b = PeerEndpoint("10.0.0.2", 2)
    diagnostics.record(a, attempt(PeerStage.DIALING), IOException("refused"), false,
      PEX_FLAG_UTP)
    diagnostics.record(a, attempt(PeerStage.CONNECTED, 5).also { it.encrypted = true }, null,
      false, 0)
    diagnostics.record(a, attempt(PeerStage.DIALING), IOException("refused"), false, 0)
    diagnostics.record(b, attempt(PeerStage.HANDSHAKING), IOException("EOF"), false,
      PEX_FLAG_ENCRYPTION or PEX_FLAG_UTP)
    diagnostics.record(b, PeerAttempt(incoming = true), IOException("EOF"), true, 0)

    assertEquals(
      "dialed 2 [handshakeClosed=1 received=1], incoming [handshakeClosed=1], encrypted 1, " +
        "pex flags utp=2 (failed 1) encryption=1 (failed 1)",
      diagnostics.summary(),
    )
    assertEquals(5, reports.size)
  }

  @Test
  fun summary_dropsTheOldestEndpointsPastCapacity() {
    val diagnostics = PeerDiagnostics(capacity = 2)
    for (port in 1..3) {
      diagnostics.record(PeerEndpoint("10.0.0.1", port), attempt(PeerStage.DIALING),
        IOException("refused"), false, 0)
    }
    assertEquals("dialed 2 [refused=2], incoming [none], encrypted 0, " +
      "pex flags utp=0 (failed 0) encryption=0 (failed 0)", diagnostics.summary())
  }
}
