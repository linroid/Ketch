package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

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
    val first = async { cache.resolve(metadata.infoHash, fetch) }
    val second = async { cache.resolve(metadata.infoHash, fetch) }
    runCurrent()
    first.cancelAndJoin()
    ready.complete(Unit)
    assertEquals(metadata.infoHash, second.await().infoHash)
    assertEquals(metadata.infoHash, cache.resolve(metadata.infoHash, fetch).infoHash)
    assertEquals(1, fetches)
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

}
