package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

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
}
