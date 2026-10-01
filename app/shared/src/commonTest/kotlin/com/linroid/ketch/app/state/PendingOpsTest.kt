package com.linroid.ketch.app.state

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

  private fun TestScope.opsScope(): CoroutineScope =
    CoroutineScope(SupervisorJob() + StandardTestDispatcher(testScheduler))

  @Test
  fun register_hidesItsTasksUntilTheWindowEndsThenCommits() = runTest {
    val ops = PendingOps(opsScope())
    val commits = mutableListOf<String>()

    ops.register("Remove", hides = setOf(keyA), commit = { commits += "remove" })
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
      label = "Remove",
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
    ops.register("Remove", hides = setOf(keyA), undo = { undone += "remove" })
    ops.register("Clear Finished", hides = setOf(keyB), undo = { undone += "clear" })
    assertEquals(listOf("Remove", "Clear Finished"), ops.ops.value.map { it.label })

    assertTrue(ops.undoLast())
    runCurrent()

    assertEquals(listOf("clear"), undone)
    assertEquals(setOf(keyA), ops.hidden.value)
  }

  @Test
  fun commit_whileRunning_keepsTheTasksHiddenAndCannotBeUndone() = runTest {
    val ops = PendingOps(opsScope())
    val removed = CompletableDeferred<Unit>()
    val op = ops.register("Remove", hides = setOf(keyA), commit = { removed.await() })

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
    ops.register("Remove", hides = setOf(keyA), commit = {
      removed.await()
      commits += "remove"
    })
    ops.register("Clear Finished", hides = setOf(keyB), commit = { commits += "clear" })

    ops.flush()
    scope.cancel()
    removed.complete(Unit)
    runCurrent()

    assertEquals(listOf("clear", "remove"), commits)
  }

  @Test
  fun commit_failure_showsTheTasksAgain() = runTest {
    val ops = PendingOps(opsScope())
    ops.register("Remove", hides = setOf(keyA), commit = { error("Connection lost") })

    advanceTimeBy(7.seconds)
    runCurrent()

    assertEquals(emptySet(), ops.hidden.value)
  }
}
