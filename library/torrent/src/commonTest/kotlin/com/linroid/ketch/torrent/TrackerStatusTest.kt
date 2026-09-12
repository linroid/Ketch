package com.linroid.ketch.torrent

import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TrackerStatusTest {
  private val request = TrackerAnnounce(InfoHash.fromBytes(ByteArray(20)), ByteArray(20), 6881, 0, 1)

  @Test
  fun snapshotsKeepStableIdsAndTrackRecoveryWithoutLeakingCredentials() = runTest {
    val secret = "https://tracker/announce?passkey=private-secret"
    var failing = true
    val tracker = TrackerTiers(listOf(listOf(secret), listOf("b"), listOf(secret, "c"))) {
      url, _, _ ->
      if (url == secret && failing) error("private-secret")
      TrackerResponse(listOf(PeerEndpoint("127.0.0.1", 1)), 3600,
        trackerId = "private-secret".encodeToByteArray(), minimumIntervalSeconds = 300)
    }
    assertEquals(listOf(0, 1, 2), tracker.status().map { it.id })
    tracker.announce(request)
    val snapshot = tracker.status()
    assertEquals(TrackerStatus.Outcome.FAILED, snapshot[0].outcome)
    assertEquals(TrackerStatus.Outcome.SUCCEEDED, snapshot[1].outcome)
    assertEquals(TrackerStatus.Outcome.NOT_CONTACTED, snapshot[2].outcome)
    tracker.announce(request)
    failing = false
    tracker.announce(request)
    val recovered = tracker.status()[0]
    assertEquals(3L, recovered.attempts)
    assertEquals(2L, recovered.failures)
    assertEquals(0L, recovered.consecutiveFailures)
    assertEquals(1, recovered.lastPeerCount)
    assertEquals(3600L, recovered.lastIntervalSeconds)
    assertEquals(300L, recovered.lastMinimumIntervalSeconds)
    assertEquals(1L, snapshot[0].attempts)
    assertEquals(1L, snapshot[0].consecutiveFailures)
    assertFalse("private-secret" in tracker.status().toString())
    assertFalse("https://" in tracker.status().toString())
  }

  @Test
  fun timedOutCandidateIsRecordedBeforeTryingTheNextTier() = runTest {
    val tracker = TrackerTiers(listOf(listOf("a"), listOf("b"))) { url, _, _ ->
      if (url == "a") withTimeout(1) { awaitCancellation() }
      TrackerResponse(emptyList(), 60)
    }
    tracker.announce(request)
    val status = tracker.status()
    assertEquals(TrackerStatus.Outcome.TIMED_OUT, status[0].outcome)
    assertEquals(1L, status[0].failures)
    assertEquals(TrackerStatus.Outcome.SUCCEEDED, status[1].outcome)
  }

  @Test
  fun exhaustedUdpRetriesAreReportedAsATimeout() = runTest {
    var closed = false
    val socket = object : TorrentDatagramSocket {
      override val local = PeerEndpoint("127.0.0.1", 1234)
      override suspend fun send(remote: PeerEndpoint, bytes: ByteArray) = Unit
      override suspend fun receive(): TorrentDatagram = awaitCancellation()
      override fun close() { closed = true }
    }
    val network = object : TorrentNetwork {
      override suspend fun bindUdp(local: PeerEndpoint): TorrentDatagramSocket = socket
      override suspend fun connect(remote: PeerEndpoint): TorrentConnection = error("Unused")
      override suspend fun listen(local: PeerEndpoint): TorrentListener = error("Unused")
      override fun close() = socket.close()
    }
    val http = TorrentHttp.default()
    try {
      val protocol = TorrentTracker(http, network, resolve = { it }, retryDelaysMs = listOf(1, 2))
      val tiers = TrackerTiers(listOf(listOf("udp://127.0.0.1:80")), protocol::announce)
      assertFailsWith<IllegalStateException> { tiers.announce(request) }
      assertEquals(TrackerStatus.Outcome.TIMED_OUT, tiers.status().single().outcome)
      assertEquals(1L, tiers.status().single().failures)
      assertEquals(3L, testScheduler.currentTime)
      assertEquals(true, closed)
    } finally { http.close(); network.close() }
  }

  @Test
  fun parentCancellationEndsTheAttemptWithoutCountingATrackerFailure() = runTest {
    var blocked = true
    val tracker = TrackerTiers(listOf(listOf("a"))) { _, _, _ ->
      if (blocked) awaitCancellation()
      TrackerResponse(emptyList(), 60)
    }
    assertFailsWith<TimeoutCancellationException> {
      withTimeout(1) { tracker.announce(request) }
    }
    assertEquals(TrackerStatus.Outcome.CANCELED, tracker.status().single().outcome)
    assertEquals(0L, tracker.status().single().failures)
    blocked = false
    tracker.announce(request)
    assertEquals(2L, tracker.status().single().attempts)
    assertEquals(TrackerStatus.Outcome.SUCCEEDED, tracker.status().single().outcome)
  }

  @Test
  fun inFlightStatusIsVisibleAndOldSnapshotsRemainInFlight() = runTest {
    lateinit var tracker: TrackerTiers
    var snapshot: List<TrackerStatus> = emptyList()
    tracker = TrackerTiers(listOf(listOf("a"))) { _, _, _ ->
      snapshot = tracker.status()
      TrackerResponse(emptyList(), 60)
    }
    tracker.announce(request)
    assertEquals(TrackerStatus.Outcome.ANNOUNCING, snapshot.single().outcome)
    assertEquals(TrackerStatus.Outcome.SUCCEEDED, tracker.status().single().outcome)
  }

  @Test
  fun cleanupFailureDoesNotInventAReplacementTrackerAttempt() = runTest {
    var failing = false
    val tracker = TrackerTiers(listOf(listOf("a"), listOf("b"))) { url, _, _ ->
      if (url == "a" && failing) error("Offline")
      TrackerResponse(emptyList(), 60)
    }
    tracker.preferCurrentTracker { error("Cleanup failed") }
    tracker.announce(request)
    failing = true
    assertFailsWith<IllegalStateException> { tracker.announce(request) }
    assertEquals(TrackerStatus.Outcome.FAILED, tracker.status()[0].outcome)
    assertEquals(TrackerStatus.Outcome.NOT_CONTACTED, tracker.status()[1].outcome)
    assertEquals(0L, tracker.status()[1].attempts)
    assertEquals(60L, tracker.status()[0].lastIntervalSeconds)
  }
}
