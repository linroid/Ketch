package com.linroid.ketch.torrent

import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TorrentV2CommitWorkerTest {
  private val bytes = byteArrayOf(1, 2, 3)
  private val document = TorrentV2Document.parse(Bencode.encode(mapOf("info" to mapOf(
    "meta version" to 2L, "piece length" to 16_384L, "file tree" to mapOf(
      "a" to mapOf("" to mapOf("length" to 3L, "pieces root" to sha256Digest(bytes))))
  ), "piece layers" to emptyMap<String, Any>())))

  private fun assembly(
    budget: TorrentBufferBudget,
    corrupt: Boolean = false,
  ): TorrentV2PieceAssembly {
    val assembly = assertNotNull(TorrentV2PieceAssembly.create(
      TorrentContentLayout.from(document.info), 0, budget))
    val value = if (corrupt) byteArrayOf(3, 2, 1) else bytes
    assembly.accept(PeerBlockExchange.Response.Block(
      PeerBlockExchange.Ticket(assembly.request(0)), value, assertNotNull(budget.reserve(512))))
    return assembly
  }

  @Test
  fun boundedQueueLeavesRejectedAssembliesOwnedAndDrainsOnCancellation() = runTest {
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-worker-bound-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(4096)
    val slots = Semaphore(1)
    val store = TorrentV2PieceStore(document, path, emptySet(), "test", budget, slots)
    store.initialize()
    val first = assembly(budget)
    val second = assembly(budget)
    val rejected = assembly(budget)
    try {
      slots.acquire()
      try {
        TorrentV2CommitWorker.run(
          store = store,
          dispatcher = StandardTestDispatcher(testScheduler),
        ) { worker ->
          assertTrue(worker.trySubmit(first))
          runCurrent()
          assertTrue(worker.trySubmit(second))
          assertFalse(worker.trySubmit(rejected))
          assertTrue(rejected.complete)
          assertTrue(worker.completions.tryReceive().isFailure)
          assertTrue(budget.allocated > 0)
        }
        assertFalse(first.complete)
        assertFalse(second.complete)
        assertTrue(rejected.complete)
        rejected.close()
        assertEquals(0, budget.allocated)
      } finally { slots.release() }
      assertFalse(store.completed())
    } finally {
      first.close()
      second.close()
      rejected.close()
      store.cleanup()
    }
  }

  @Test
  fun completionsFollowIntegrityAndStoragePublicationAndFailuresReturnToTheActor() = runTest {
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-worker-results-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(4096)
    val store = TorrentV2PieceStore(document, path, emptySet(), "test", budget, Semaphore(1))
    try {
      store.initialize()
      TorrentV2CommitWorker.run(store) { worker ->
        assertTrue(worker.trySubmit(assembly(budget, corrupt = true)))
        val rejected = assertIs<TorrentV2CommitWorker.Completion.Committed>(
          worker.completions.receive())
        assertFalse(rejected.verified)
        assertFalse(store.completed())
        assertEquals(0, budget.allocated)
        assertTrue(worker.trySubmit(assembly(budget)))
        val committed = assertIs<TorrentV2CommitWorker.Completion.Committed>(
          worker.completions.receive())
        assertTrue(committed.verified)
        assertTrue(store.completed())
        assertEquals(3L, torrentFileSystem.metadata(path / "a").size)
        assertEquals(0, budget.allocated)
      }
      store.close()
      TorrentV2CommitWorker.run(store) { worker ->
        assertTrue(worker.trySubmit(assembly(budget)))
        assertIs<TorrentV2CommitWorker.Completion.Failed>(worker.completions.receive())
        assertEquals(0, budget.allocated)
      }
    } finally { store.cleanup() }
  }
}
