package com.linroid.kdown.task
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TaskStateTest {

  @Test
  fun isTerminal_trueForCompletedFailedCanceled() {
    assertTrue(TaskState.COMPLETED.isTerminal)
    assertTrue(TaskState.FAILED.isTerminal)
    assertTrue(TaskState.CANCELED.isTerminal)
  }

  @Test
  fun isTerminal_falseForActiveStates() {
    assertFalse(TaskState.PENDING.isTerminal)
    assertFalse(TaskState.DOWNLOADING.isTerminal)
    assertFalse(TaskState.PAUSED.isTerminal)
  }

  @Test
  fun isRestorable_trueForPendingDownloadingPaused() {
    assertTrue(TaskState.PENDING.isRestorable)
    assertTrue(TaskState.DOWNLOADING.isRestorable)
    assertTrue(TaskState.PAUSED.isRestorable)
  }

  @Test
  fun isRestorable_falseForTerminalStates() {
    assertFalse(TaskState.COMPLETED.isRestorable)
    assertFalse(TaskState.FAILED.isRestorable)
    assertFalse(TaskState.CANCELED.isRestorable)
  }
}
