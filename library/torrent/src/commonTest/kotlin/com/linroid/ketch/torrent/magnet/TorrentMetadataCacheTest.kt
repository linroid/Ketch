package com.linroid.ketch.torrent.magnet

import com.linroid.ketch.torrent.TorrentDiscoveryPrivacy
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TorrentMetadataCacheTest {
  @Test
  fun sharedAndCachedResultsExcludeCallerTrackerCredentials() = runTest {
    val info = Bencode.encode(mapOf("name" to "empty", "length" to 0L,
      "piece length" to 1L, "pieces" to ByteArray(0)))
    val first = TorrentMetadata.fromBencode(metainfoFromInfo(info,
      listOf(listOf("https://tracker.example/first-passkey"))))
    val second = TorrentMetadata.fromBencode(metainfoFromInfo(info,
      listOf(listOf("https://tracker.example/second-passkey"))))
    val cache = TorrentMetadataCache(backgroundScope)
    val gate = CompletableDeferred<Unit>()
    var fetches = 0
    val one = async { cache.resolve(first.infoHash) { fetches++; gate.await(); first } }
    val two = async { cache.resolve(second.infoHash) { fetches++; gate.await(); second } }
    gate.complete(Unit)
    for (result in listOf(one.await(), two.await(), cache.get(first.infoHash)!!)) {
      assertEquals(emptyList(), result.trackers)
      assertEquals(emptyList(), result.trackerTiers)
      assertEquals(emptyList(), TorrentMetadata.fromBencode(result.metainfoBytes).trackers)
    }
    assertEquals(1, fetches)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun restrictedCallersNeverJoinPendingPublicDiscovery() = runTest {
    val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
      "name" to "empty", "length" to 0L, "piece length" to 16_384L, "pieces" to ByteArray(0)
    ))))
    val cache = TorrentMetadataCache(backgroundScope)
    val gate = CompletableDeferred<Unit>()
    val used = mutableListOf<TorrentDiscoveryPrivacy>()
    fun fetch(privacy: TorrentDiscoveryPrivacy): suspend () -> TorrentMetadata = {
      used += privacy
      gate.await()
      metadata
    }
    val public = async {
      cache.resolve(metadata.infoHash, TorrentDiscoveryPrivacy.PUBLIC,
        fetch = fetch(TorrentDiscoveryPrivacy.PUBLIC))
    }
    runCurrent()
    val restricted = async {
      cache.resolve(metadata.infoHash, TorrentDiscoveryPrivacy.TRACKER_ONLY,
        fetch = fetch(TorrentDiscoveryPrivacy.TRACKER_ONLY))
    }
    runCurrent()
    gate.complete(Unit)
    assertEquals(metadata.infoHash, public.await().infoHash)
    assertEquals(metadata.infoHash, restricted.await().infoHash)
    // The restricted caller ran its own tracker-only fetch instead of joining the public one.
    assertEquals(listOf(TorrentDiscoveryPrivacy.PUBLIC, TorrentDiscoveryPrivacy.TRACKER_ONLY),
      used)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun samePrivacyStillSharesOneInFlightFetch() = runTest {
    val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
      "name" to "empty", "length" to 0L, "piece length" to 16_384L, "pieces" to ByteArray(0)
    ))))
    val cache = TorrentMetadataCache(backgroundScope)
    val gate = CompletableDeferred<Unit>()
    var fetches = 0
    val fetch: suspend () -> TorrentMetadata = { fetches++; gate.await(); metadata }
    val one = async {
      cache.resolve(metadata.infoHash, TorrentDiscoveryPrivacy.TRACKER_ONLY, fetch = fetch)
    }
    runCurrent()
    val two = async {
      cache.resolve(metadata.infoHash, TorrentDiscoveryPrivacy.TRACKER_ONLY, fetch = fetch)
    }
    runCurrent()
    gate.complete(Unit)
    assertEquals(metadata.infoHash, one.await().infoHash)
    assertEquals(metadata.infoHash, two.await().infoHash)
    assertEquals(1, fetches)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun sharedFetch_survivesOneWaiterCancelAndAvoidsHandoffRefetch() = runTest {
    val metadata = TorrentMetadata.fromBencode(Bencode.encode(mapOf("info" to mapOf(
      "name" to "empty", "length" to 0L, "piece length" to 16_384L, "pieces" to ByteArray(0)
    ))))
    val cache = TorrentMetadataCache(backgroundScope)
    val ready = CompletableDeferred<Unit>()
    var fetches = 0
    val fetch: suspend () -> TorrentMetadata = {
      fetches++
      ready.await()
      metadata
    }
    val first = async { cache.resolve(metadata.infoHash, fetch = fetch) }
    val second = async { cache.resolve(metadata.infoHash, fetch = fetch) }
    runCurrent()
    first.cancelAndJoin()
    ready.complete(Unit)
    assertEquals(metadata.infoHash, second.await().infoHash)
    assertEquals(metadata.infoHash, cache.resolve(metadata.infoHash, fetch = fetch).infoHash)
    assertEquals(1, fetches)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun resolve_lastWaiterLeaves_cancelsTheFetchOnceItsGraceEnds() = runTest {
    val metadata = fixture("a")
    val cache = TorrentMetadataCache(backgroundScope)
    var started = 0
    var stopped = 0
    val hanging: suspend () -> TorrentMetadata = {
      started++
      try { awaitCancellation() } finally { stopped++ }
    }
    val first = async { cache.resolve(metadata.infoHash, fetch = hanging) }
    val second = async { cache.resolve(metadata.infoHash, fetch = hanging) }
    runCurrent()
    first.cancelAndJoin()
    runCurrent()
    // The other caller still waits for it.
    assertEquals(0, stopped)
    second.cancelAndJoin()
    // Nothing waits for it: it goes on for its grace, keeping its place...
    advanceTimeBy(ABANDONED_LOOKUP_GRACE - 1.milliseconds)
    assertEquals(0, stopped)
    assertEquals(1, cache.pendingFetches)
    // ...and then stops and frees it.
    advanceTimeBy(2.milliseconds)
    assertEquals(1, stopped)
    assertEquals(0, cache.pendingFetches)
    // A later caller runs a fetch of its own.
    assertEquals(metadata.infoHash, cache.resolve(metadata.infoHash) { metadata }.infoHash)
    assertEquals(1, started)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun resolve_callerComesBackWithinTheGrace_carriesOnWithTheFetch() = runTest {
    val metadata = fixture("a")
    val cache = TorrentMetadataCache(backgroundScope)
    val found = CompletableDeferred<Unit>()
    var started = 0
    var stopped = 0
    val fetch: suspend () -> TorrentMetadata = {
      started++
      try { found.await() } catch (e: CancellationException) { stopped++; throw e }
      metadata
    }
    val paused = async { cache.resolve(metadata.infoHash, fetch = fetch) }
    runCurrent()
    paused.cancelAndJoin()
    advanceTimeBy(ABANDONED_LOOKUP_GRACE / 2)
    // A task resumed, or a preview's link added, joins the lookup still going on...
    val resumed = async { cache.resolve(metadata.infoHash, fetch = fetch) }
    runCurrent()
    // ...which goes on past the grace its first caller left it, while the other waits.
    advanceTimeBy(ABANDONED_LOOKUP_GRACE)
    assertEquals(0, stopped)
    assertEquals(1, cache.pendingFetches)
    found.complete(Unit)
    assertEquals(metadata.infoHash, resumed.await().infoHash)
    assertEquals(1, started)
    assertEquals(0, cache.pendingFetches)
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun resolve_callerWithinTheGraceAsksNothingElse_carriesOnWithTheFetch() = runTest {
    val metadata = fixture("a")
    val cache = TorrentMetadataCache(backgroundScope)
    val found = CompletableDeferred<Unit>()
    var started = 0
    val fetch: suspend () -> TorrentMetadata = { started++; found.await(); metadata }
    val left = async {
      cache.resolve(metadata.infoHash, sources = LookupSources(setOf(TRACKER, OTHER_TRACKER),
        setOf(PEER)), fetch = fetch)
    }
    runCurrent()
    left.cancelAndJoin()
    advanceTimeBy(ABANDONED_LOOKUP_GRACE / 2)
    // A link naming some of the trackers and peers the lookup asks carries on with it.
    val narrower = async {
      cache.resolve(metadata.infoHash, sources = LookupSources(setOf(OTHER_TRACKER)),
        fetch = fetch)
    }
    runCurrent()
    found.complete(Unit)
    assertEquals(metadata.infoHash, narrower.await().infoHash)
    assertEquals(1, started)
  }

  @Test
  fun resolve_callerWithinTheGraceNamesAnotherTracker_startsAFetchOfItsOwn() =
    callerWithinTheGrace_startsAFetchOfItsOwn(LookupSources(setOf(TRACKER, OTHER_TRACKER)))

  @Test
  fun resolve_callerWithinTheGraceNamesAnotherPeer_startsAFetchOfItsOwn() =
    callerWithinTheGrace_startsAFetchOfItsOwn(LookupSources(setOf(TRACKER), setOf(PEER)))

  /**
   * Leaves a fetch that asks [TRACKER] in its grace, then resolves the same hash from [wider]
   * sources, and checks that the left fetch stopped at once and the caller's own took its place.
   */
  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  private fun callerWithinTheGrace_startsAFetchOfItsOwn(wider: LookupSources) = runTest {
    val metadata = fixture("a")
    val cache = TorrentMetadataCache(backgroundScope)
    val started = mutableListOf<String>()
    val stopped = mutableListOf<String>()
    fun fetch(name: String): suspend () -> TorrentMetadata = {
      started += name
      try { awaitCancellation() } finally { stopped += name }
    }
    val left = async {
      cache.resolve(metadata.infoHash, sources = LookupSources(setOf(TRACKER)),
        fetch = fetch("left"))
    }
    runCurrent()
    left.cancelAndJoin()
    advanceTimeBy(ABANDONED_LOOKUP_GRACE / 2)
    val caller = async {
      cache.resolve(metadata.infoHash, sources = wider, fetch = fetch("caller's"))
    }
    runCurrent()
    // The left fetch, which would not ask everywhere the caller's link names, stopped before its
    // grace ended, and the caller's own runs in its place.
    assertEquals(listOf("left", "caller's"), started)
    assertEquals(listOf("left"), stopped)
    assertEquals(1, cache.pendingFetches)
    caller.cancelAndJoin()
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun resolve_fetchReplacedInItsGrace_startsTheCallersOnceItEnded() = runTest {
    val metadata = fixture("a")
    val cache = TorrentMetadataCache(backgroundScope)
    val started = mutableListOf<String>()
    val left = async {
      cache.resolve(metadata.infoHash, sources = LookupSources(setOf(TRACKER))) {
        started += "left"
        try { awaitCancellation() } finally {
          // What it says as it ends, such as its trackers' `stopped`, takes a while.
          withContext(NonCancellable) { delay(1.seconds) }
        }
      }
    }
    runCurrent()
    left.cancelAndJoin()
    val caller = async {
      cache.resolve(metadata.infoHash, sources = LookupSources(setOf(OTHER_TRACKER))) {
        started += "caller's"
        awaitCancellation()
      }
    }
    runCurrent()
    // The caller's fetch waits for the one it replaces to end...
    assertEquals(listOf("left"), started)
    advanceTimeBy(1.seconds + 1.milliseconds)
    // ...and then starts.
    assertEquals(listOf("left", "caller's"), started)
    caller.cancelAndJoin()
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun resolve_pastTheBound_waitsForAFetchToEnd() = runTest {
    val cache = TorrentMetadataCache(backgroundScope)
    val started = mutableListOf<Int>()
    val stopped = mutableListOf<Int>()
    val callers = (0..MAX_PENDING_METADATA).map { index ->
      async {
        cache.resolve(fixture("m$index").infoHash) {
          started += index
          try { awaitCancellation() } finally { stopped += index }
        }
      }
    }
    runCurrent()
    // The last one waits rather than failing.
    assertEquals((0..<MAX_PENDING_METADATA).toList(), started)
    assertTrue(callers.last().isActive)
    // Their callers gone, the others stop once their grace ends, and it starts.
    callers.dropLast(1).forEach { it.cancelAndJoin() }
    runCurrent()
    assertEquals(MAX_PENDING_METADATA, started.size)
    advanceTimeBy(ABANDONED_LOOKUP_GRACE + 1.milliseconds)
    assertEquals((0..<MAX_PENDING_METADATA).toList(), stopped.sorted())
    assertEquals((0..MAX_PENDING_METADATA).toList(), started)
    assertEquals(1, cache.pendingFetches)
    callers.last().cancelAndJoin()
    advanceTimeBy(ABANDONED_LOOKUP_GRACE + 1.milliseconds)
    assertEquals(0, cache.pendingFetches)
  }

  @Test
  fun evictionReplacementAndCloseReleaseParentReservations() = runTest {
    val first = fixture("a")
    val second = fixture("b")
    val third = fixture("c")
    val size = cacheWeight(first).toInt()
    val root = TorrentBufferBudget(size * 2)
    val cache = TorrentMetadataCache(backgroundScope, size * 2, root)
    cache.put(first)
    cache.put(second)
    assertNotNull(cache.get(first.infoHash)) // Refresh first; second is now least recently used.
    cache.put(third)
    assertNull(cache.get(second.infoHash))
    assertNotNull(cache.get(first.infoHash))
    cache.put(third) // Replacement must not leak the earlier reservation.
    assertEquals(size * 2, cache.retainedBytes)
    assertEquals(size * 2, root.allocated)
    cache.close()
    cache.close()
    assertEquals(0, root.allocated)
    assertFailsWith<IllegalStateException> { cache.put(first) }
    assertFailsWith<IllegalStateException> { cache.get(first.infoHash) }
  }

  @Test
  fun overBudgetResultsAreReturnedWithoutRetainingAnEntry() = runTest {
    val metadata = fixture("a")
    val size = cacheWeight(metadata).toInt()
    val root = TorrentBufferBudget(size)
    val cache = TorrentMetadataCache(backgroundScope, size, root)
    val other = assertNotNull(root.reserve(size))
    assertEquals(metadata.infoHash, cache.put(metadata).infoHash)
    assertNull(cache.get(metadata.infoHash))
    assertEquals(0, cache.retainedBytes)
    other.close()
    cache.put(metadata)
    assertEquals(size, root.allocated)
    cache.close()
    val small = TorrentMetadataCache(backgroundScope, size - 1, root)
    assertEquals(metadata.infoHash, small.put(metadata).infoHash)
    assertNull(small.get(metadata.infoHash))
    assertEquals(0, root.allocated)
  }

  @Test
  fun closeCancelsSharedFetchAndWaitsForItsFinalizer() = runTest {
    val metadata = fixture("a")
    val cache = TorrentMetadataCache(backgroundScope)
    val ready = CompletableDeferred<Unit>()
    var finalized = false
    val caller = async {
      cache.resolve(metadata.infoHash) {
        ready.complete(Unit)
        try { awaitCancellation() } finally { finalized = true }
      }
    }
    ready.await()
    cache.close()
    assertTrue(finalized)
    caller.join()
    assertTrue(caller.isCancelled)
    assertEquals(0, cache.retainedBytes)
  }

  @Test
  fun ownerCancellationReleasesRetainedMetadata() = runTest {
    val ownerJob = SupervisorJob()
    val owner = CoroutineScope(backgroundScope.coroutineContext + ownerJob)
    val root = TorrentBufferBudget(4096)
    val cache = TorrentMetadataCache(owner, 4096, root)
    try {
      cache.put(fixture("a"))
      assertTrue(root.allocated > 0)
    } finally {
      owner.coroutineContext[Job]!!.cancelAndJoin()
    }
    assertEquals(0, root.allocated)
  }

  @Test
  fun close_fetchInItsGrace_letsStructuredOwnerCompleteAtOnce() = runTest {
    withTimeout(1000) {
      coroutineScope {
        val cache = TorrentMetadataCache(this)
        val left = async { cache.resolve(fixture("a").infoHash) { awaitCancellation() } }
        runCurrent()
        left.cancelAndJoin()
        // The fetch nothing waits for ends with the cache, not with its grace.
        cache.close()
      }
    }
  }

  @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
  @Test
  fun close_fetchWhoseGraceEndedStillEnding_waitsForIt() = runTest {
    val cache = TorrentMetadataCache(backgroundScope)
    var ended = false
    val left = async {
      cache.resolve(fixture("a").infoHash) {
        try { awaitCancellation() } finally {
          // What it says as it ends, such as its trackers' `stopped`, takes a while.
          withContext(NonCancellable) { delay(1.seconds) }
          ended = true
        }
      }
    }
    runCurrent()
    left.cancelAndJoin()
    // Its grace over, it is no longer pending, but has yet to end...
    advanceTimeBy(ABANDONED_LOOKUP_GRACE + 1.milliseconds)
    assertEquals(0, cache.pendingFetches)
    assertFalse(ended)
    // ...which the cache's close waits for.
    cache.close()
    assertTrue(ended)
  }

  @Test
  fun explicitCloseAllowsStructuredOwnerToComplete() = runTest {
    withTimeout(1000) {
      coroutineScope {
        val cache = TorrentMetadataCache(this)
        cache.put(fixture("a"))
        cache.close()
      }
    }
  }

  private fun fixture(name: String): TorrentMetadata = TorrentMetadata.fromBencode(
    Bencode.encode(mapOf("info" to mapOf(
      "name" to name, "length" to 0L, "piece length" to 16_384L, "pieces" to ByteArray(0)
    )))
  )

  private companion object {
    const val TRACKER = "udp://tracker.test:6969/announce"
    const val OTHER_TRACKER = "http://other.test/announce"
    const val PEER = "203.0.113.5:6881"
  }
}
