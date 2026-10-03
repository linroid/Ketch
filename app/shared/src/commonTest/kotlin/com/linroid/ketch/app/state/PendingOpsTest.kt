package com.linroid.ketch.app.state

import com.linroid.ketch.app.i18n.verbatim
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class PendingOpsTest {

  private val keyA = TaskKey(LOCAL_DEVICE_ID, "a")
  private val keyB = TaskKey(LOCAL_DEVICE_ID, "b")
  private val remove = verbatim("Undo remove")
  private val clearFinished = verbatim("Undo clear finished")

  private fun TestScope.opsScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

  @Test
  fun register_hidesItsTasksUntilTheWindowEndsThenCommits() = runTest {
    val ops = PendingOps(opsScope())
    val commits = mutableListOf<String>()

    ops.register(remove, hides = setOf(keyA), commit = { commits += "remove" })
    runCurrent()
    assertEquals(setOf(keyA), ops.hidden.value)

    advanceTimeBy(6.seconds - 1.milliseconds)
    runCurrent()
    assertEquals(emptyList(), commits)

    advanceTimeBy(2.milliseconds)
    runCurrent()
    assertEquals(listOf("remove"), commits)
    assertEquals(emptySet(), ops.hidden.value)
    assertEquals(emptyList(), ops.ops.value)
  }

  @Test
  fun undo_beforeTheWindowEnds_showsTheTasksAndNeverCommits() = runTest {
    val ops = PendingOps(opsScope())
    val calls = mutableListOf<String>()
    val op = ops.register(
      undoTitle = remove,
      hides = setOf(keyA),
      commit = { calls += "commit" },
      undo = { calls += "undo" },
    )

    assertTrue(ops.undo(op.id))
    assertEquals(emptySet(), ops.hidden.value)
    advanceTimeBy(10.seconds)
    runCurrent()

    assertEquals(listOf("undo"), calls)
    assertFalse(ops.undo(op.id))
  }

  @Test
  fun undoLast_twoPendingOps_undoesTheNewest() = runTest {
    val ops = PendingOps(opsScope())
    val undone = mutableListOf<String>()
    ops.register(remove, hides = setOf(keyA), undo = { undone += "remove" })
    ops.register(clearFinished, hides = setOf(keyB), undo = { undone += "clear" })
    assertEquals(listOf(remove, clearFinished), ops.ops.value.map { it.undoTitle })

    assertTrue(ops.undoLast())
    runCurrent()

    assertEquals(listOf("clear"), undone)
    assertEquals(setOf(keyA), ops.hidden.value)
  }

  @Test
  fun commit_whileRunning_keepsTheTasksHiddenAndCannotBeUndone() = runTest {
    val ops = PendingOps(opsScope())
    val removed = CompletableDeferred<Unit>()
    val op = ops.register(remove, hides = setOf(keyA), commit = { removed.await() })

    ops.commitNow(op.id)
    runCurrent()
    assertEquals(setOf(keyA), ops.hidden.value)
    assertFalse(ops.undo(op.id))

    removed.complete(Unit)
    runCurrent()
    assertEquals(emptySet(), ops.hidden.value)
  }

  @Test
  fun flush_scopeCancelledRightAfter_stillCommitsEverything() = runTest {
    val scope = opsScope()
    val ops = PendingOps(scope)
    val commits = mutableListOf<String>()
    val removed = CompletableDeferred<Unit>()
    ops.register(remove, hides = setOf(keyA), commit = {
      removed.await()
      commits += "remove"
    })
    ops.register(clearFinished, hides = setOf(keyB), commit = { commits += "clear" })

    ops.flush()
    scope.cancel()
    removed.complete(Unit)
    runCurrent()

    assertEquals(listOf("clear", "remove"), commits)
  }

  @Test
  fun flush_commitsStillRunning_completesOnceTheyFinish() = runTest {
    val scope = opsScope()
    val ops = PendingOps(scope)
    val removed = CompletableDeferred<Unit>()
    val cleared = CompletableDeferred<Unit>()
    val first = ops.register(remove, hides = setOf(keyA), commit = { removed.await() })
    ops.register(clearFinished, hides = setOf(keyB), commit = { cleared.await() })
    ops.commitNow(first.id)
    runCurrent()

    val done = ops.flush()
    scope.cancel()
    cleared.complete(Unit)
    runCurrent()
    assertFalse(done.isCompleted)

    removed.complete(Unit)
    runCurrent()
    assertTrue(done.isCompleted)
  }

  @Test
  fun flush_nothingPending_isAlreadyComplete() = runTest {
    val ops = PendingOps(opsScope())

    assertTrue(ops.flush().isCompleted)
  }

  @Test
  fun commit_failure_showsTheTasksAgain() = runTest {
    val ops = PendingOps(opsScope())
    ops.register(remove, hides = setOf(keyA), commit = { error("Connection lost") })

    advanceTimeBy(7.seconds)
    runCurrent()

    assertEquals(emptySet(), ops.hidden.value)
  }
}
