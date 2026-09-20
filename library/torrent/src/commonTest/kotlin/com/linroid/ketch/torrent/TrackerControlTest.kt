package com.linroid.ketch.torrent

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
class TrackerControlTest {
  @Test
  fun scrapeSharesManualAdmissionAndCancellationReleasesPeriodicPolling() = runTest {
    val budget = TorrentBufferBudget(100_000)
    val entered = CompletableDeferred<Unit>()
    var cleaned = false
    TrackerControl.run(budget, operation = { TrackerResponse(emptyList(), 60) },
      publish = {}, readStatus = { emptyList() }, publishStatus = {}, scrape = {
        entered.complete(Unit)
        try { awaitCancellation() } finally { cleaned = true }
      },
    ) { control ->
      val request = launch { control.scrape() }
      entered.await()
      assertFalse(control.scrape())
      assertFalse(control.reannounce())
      val periodic = async { control.poll() }
      runCurrent()
      assertFalse(periodic.isCompleted)
      request.cancelAndJoin()
      assertTrue(cleaned)
      assertTrue(periodic.await())
      assertTrue(control.reannounce())
    }
    assertEquals(0, budget.allocated)
  }

  @Test
  fun rejectsInsufficientBudgetBeforePublishingAControl() = runTest {
    val budget = TorrentBufferBudget(1)
    var entered = false
    assertFailsWith<IllegalStateException> {
      TrackerControl.run(budget, operation = { error("Unused") }, publish = {},
        readStatus = { emptyList() }, publishStatus = {}) { entered = true }
    }
    assertFalse(entered)
    assertEquals(0, budget.allocated)
  }

  @Test
  fun manualAdmissionIsBoundedAndSerializedWithPeriodicPolling() = runTest {
    val budget = TorrentBufferBudget(100_000)
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val calls = mutableListOf<Boolean>()
    var published = 0
    TrackerControl.run(budget, operation = { manual ->
      calls += manual
      if (!manual) { entered.complete(Unit); release.await() }
      TrackerResponse(emptyList(), 60)
    }, publish = { published++ }, readStatus = { emptyList() }, publishStatus = {}) { control ->
      val poll = async { control.poll() }
      entered.await()
      val first = async { control.reannounce() }
      runCurrent()
      assertFalse(control.reannounce())
      assertEquals(listOf(false), calls)
      release.complete(Unit)
      assertTrue(poll.await())
      assertTrue(first.await())
      assertEquals(listOf(false, true), calls)
      assertEquals(2, published)
    }
    assertEquals(0, budget.allocated)
  }

  @Test
  fun callerCancellationJoinsOperationAndReleasesManualSlot() = runTest {
    val budget = TorrentBufferBudget(100_000)
    val entered = CompletableDeferred<Unit>()
    var blocked = true
    var canceled = false
    TrackerControl.run(budget, operation = {
      if (blocked) {
        entered.complete(Unit)
        try { awaitCancellation() } finally { canceled = true }
      }
      TrackerResponse(emptyList(), 60)
    }, publish = {}, readStatus = { emptyList() }, publishStatus = {}) { control ->
      val request = launch { control.reannounce() }
      entered.await()
      request.cancelAndJoin()
      assertTrue(canceled)
      blocked = false
      assertTrue(control.reannounce())
    }
    assertEquals(0, budget.allocated)
  }

  @Test
  fun ownerShutdownJoinsManualCleanupBeforeReturningBudgetCredit() = runTest {
    val budget = TorrentBufferBudget(100_000)
    val ready = CompletableDeferred<TrackerControl>()
    val entered = CompletableDeferred<Unit>()
    val canceling = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()
    val finish = CompletableDeferred<Unit>()
    val owner = launch {
      TrackerControl.run(budget, operation = {
        entered.complete(Unit)
        try { awaitCancellation() } finally {
          withContext(NonCancellable) { canceling.complete(Unit); release.await() }
        }
      }, publish = {}, readStatus = { emptyList() }, publishStatus = {}) { control ->
        ready.complete(control)
        finish.await()
      }
    }
    val control = ready.await()
    val request = launch { control.reannounce() }
    entered.await()
    finish.complete(Unit)
    canceling.await()
    assertTrue(budget.allocated > 0)
    assertFalse(owner.isCompleted)
    release.complete(Unit)
    owner.join()
    request.join()
    assertEquals(0, budget.allocated)
  }
}
