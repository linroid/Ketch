package com.linroid.ketch.torrent

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.ChannelResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.FileSystem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertNotSame
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
          assertNotNull(worker.trySubmit(first))
          val retained = budget.allocated
          first.close()
          assertEquals(retained, budget.allocated)
          assertFailsWith<IllegalStateException> { worker.trySubmit(first) }
          runCurrent()
          assertNotNull(worker.trySubmit(second))
          assertNull(worker.trySubmit(rejected))
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
        val first = assertNotNull(worker.trySubmit(assembly(budget, corrupt = true)))
        val rejected = assertIs<TorrentV2CommitWorker.Completion.Committed>(
          worker.completions.receive())
        assertSame(first, rejected.ticket)
        assertFalse(rejected.verified)
        assertFalse(store.completed())
        assertEquals(0, budget.allocated)
        val second = assertNotNull(worker.trySubmit(assembly(budget)))
        val committed = assertIs<TorrentV2CommitWorker.Completion.Committed>(
          worker.completions.receive())
        assertSame(second, committed.ticket)
        assertEquals(first.index, second.index)
        assertNotSame(first, second)
        assertTrue(committed.verified)
        assertTrue(store.completed())
        assertEquals(3L, torrentFileSystem.metadata(path / "a").size)
        assertEquals(0, budget.allocated)
      }
      store.close()
      TorrentV2CommitWorker.run(store) { worker ->
        assertNotNull(worker.trySubmit(assembly(budget)))
        assertIs<TorrentV2CommitWorker.Completion.Failed>(worker.completions.receive())
        assertEquals(0, budget.allocated)
      }
    } finally { store.cleanup() }
  }

  @Test
  fun cancelledWorkerClosesCompletionsWithItsCancellation() = runTest {
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-worker-cancel-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(4096)
    val store = TorrentV2PieceStore(document, path, emptySet(), "test", budget, Semaphore(1))
    store.initialize()
    try {
      var seen: ChannelResult<TorrentV2CommitWorker.Completion>? = null
      val job = launch {
        TorrentV2CommitWorker.run(store, dispatcher = StandardTestDispatcher(testScheduler)) {
          // A loop may see the worker's channel close before its own cancellation lands: the
          // close must then carry that cancellation, not read as a worker that stopped.
          seen = withContext(NonCancellable) { it.completions.receiveCatching() }
          awaitCancellation()
        }
      }
      runCurrent()
      job.cancel()
      runCurrent()
      job.join()
      val result = assertNotNull(seen)
      assertTrue(result.isClosed)
      assertIs<CancellationException>(result.exceptionOrNull())
    } finally { store.cleanup() }
  }

  @Test
  fun notWantedCommit_completesAsDiscarded() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("a" to 5, "b" to 7), pieceLength = 16_384)
    val path = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-worker-discard-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val budget = TorrentBufferBudget(65_536)
    // Only a is selected; b's piece arrives from a peer that still had it asked for.
    val store = TorrentV2PieceStore(fixture.document, path, setOf("0"), "test", budget,
      Semaphore(1))
    try {
      store.initialize()
      val assembly = assertNotNull(TorrentV2PieceAssembly.create(fixture.layout, 1, budget))
      assembly.accept(PeerBlockExchange.Response.Block(
        PeerBlockExchange.Ticket(assembly.request(0)), fixture.v2Piece(1),
        assertNotNull(budget.reserve(512))))
      TorrentV2CommitWorker.run(store, dispatcher = StandardTestDispatcher(testScheduler)) {
        assertNotNull(it.trySubmit(assembly))
        val completion = it.completions.receive()
        assertIs<TorrentV2CommitWorker.Completion.Discarded>(completion)
        assertEquals(1, completion.ticket.index)
      }
      assertFalse(torrentFileSystem.exists(path / "b"))
      assertFalse(store.verifiedPieces()[1])
      assertEquals(0, budget.allocated)
    } finally {
      store.cleanup()
    }
  }
}
