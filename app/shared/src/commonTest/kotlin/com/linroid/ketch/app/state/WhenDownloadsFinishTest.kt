package com.linroid.ketch.app.state

import com.linroid.ketch.app.feedback.MessageCenter
import com.linroid.ketch.app.feedback.MessageLevel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class WhenDownloadsFinishTest {
  private val busy = ForegroundStatus(downloading = 1)
  private val idle = ForegroundStatus()

  private class RecordingActions(
    override val supported: List<FinishAction> = FinishAction.entries.drop(1),
    private val failure: Exception? = null,
  ) : FinishActions {
    val performed = mutableListOf<FinishAction>()

    override suspend fun perform(action: FinishAction) {
      performed += action
      failure?.let { throw it }
    }
  }

  private class Fixture(
    scope: CoroutineScope,
    val actions: RecordingActions = RecordingActions(),
  ) {
    val messages = MessageCenter()
    val statuses = MutableStateFlow(ForegroundStatus())
    val whenDone = WhenDownloadsFinish(actions, messages, scope, grace = 60.seconds)
  }

  private fun TestScope.fixture(actions: RecordingActions = RecordingActions()): Fixture {
    val fixture = Fixture(backgroundScope, actions)
    backgroundScope.launch { fixture.whenDone.follow(fixture.statuses) }
    runCurrent()
    return fixture
  }

  @Test
  fun options_startWithNothing() = runTest {
    val whenDone = Fixture(backgroundScope, RecordingActions(listOf(FinishAction.Quit))).whenDone
    assertEquals(listOf(FinishAction.Nothing, FinishAction.Quit), whenDone.options)
    assertFailsWith<IllegalArgumentException> { whenDone.choose(FinishAction.ShutDown) }
  }

  @Test
  fun downloadsFinish_warnsThenRunsOnce() = runTest {
    val f = fixture()
    f.whenDone.choose(FinishAction.ShutDown)
    f.statuses.value = busy
    runCurrent()
    f.statuses.value = idle
    runCurrent()

    assertTrue(f.whenDone.pending)
    val warning = f.messages.active.value.single()
    assertEquals(MessageLevel.Warning, warning.level)
    assertTrue(warning.notify)

    advanceTimeBy(59.seconds)
    runCurrent()
    assertEquals(emptyList(), f.actions.performed)

    advanceTimeBy(2.seconds)
    runCurrent()
    assertEquals(listOf(FinishAction.ShutDown), f.actions.performed)
    assertEquals(FinishAction.Nothing, f.whenDone.action)
    assertFalse(f.whenDone.pending)
    assertTrue(f.messages.active.value.isEmpty())

    // Chosen once: the next downloads finish without it.
    f.statuses.value = busy
    runCurrent()
    f.statuses.value = idle
    advanceTimeBy(120.seconds)
    runCurrent()
    assertEquals(listOf(FinishAction.ShutDown), f.actions.performed)
  }

  @Test
  fun chosenWhileIdle_waitsForDownloadsToRunAndFinish() = runTest {
    val f = fixture()
    f.whenDone.choose(FinishAction.Sleep)
    advanceTimeBy(120.seconds)
    runCurrent()
    assertFalse(f.whenDone.pending)

    f.statuses.value = busy
    runCurrent()
    f.statuses.value = idle
    advanceTimeBy(61.seconds)
    runCurrent()
    assertEquals(listOf(FinishAction.Sleep), f.actions.performed)
  }

  @Test
  fun chosenWhileBusy_runsWhenTheyFinish() = runTest {
    val f = fixture()
    f.statuses.value = busy
    runCurrent()
    f.whenDone.choose(FinishAction.Quit)
    f.statuses.value = idle
    advanceTimeBy(61.seconds)
    runCurrent()
    assertEquals(listOf(FinishAction.Quit), f.actions.performed)
  }

  @Test
  fun cancel_withdrawsTheWarningAndForgetsTheAction() = runTest {
    val f = fixture()
    f.whenDone.choose(FinishAction.ShutDown)
    f.statuses.value = busy
    runCurrent()
    f.statuses.value = idle
    runCurrent()

    f.messages.active.value.single().actions.first().onClick()
    advanceTimeBy(120.seconds)
    runCurrent()

    assertEquals(emptyList(), f.actions.performed)
    assertEquals(FinishAction.Nothing, f.whenDone.action)
    assertTrue(f.messages.active.value.isEmpty())
  }

  @Test
  fun runNow_runsWithoutWaiting() = runTest {
    val f = fixture()
    f.whenDone.choose(FinishAction.Sleep)
    f.statuses.value = busy
    runCurrent()
    f.statuses.value = idle
    runCurrent()

    f.messages.active.value.single().actions.last().onClick()
    runCurrent()

    assertEquals(listOf(FinishAction.Sleep), f.actions.performed)
    assertTrue(f.messages.active.value.isEmpty())
  }

  @Test
  fun downloadsStartAgain_putTheActionOff() = runTest {
    val f = fixture()
    f.whenDone.choose(FinishAction.ShutDown)
    f.statuses.value = busy
    runCurrent()
    f.statuses.value = idle
    advanceTimeBy(30.seconds)
    runCurrent()

    f.statuses.value = ForegroundStatus(queued = 1)
    advanceTimeBy(60.seconds)
    runCurrent()
    assertEquals(emptyList(), f.actions.performed)
    assertFalse(f.whenDone.pending)
    assertTrue(f.messages.active.value.isEmpty())
    assertEquals(FinishAction.ShutDown, f.whenDone.action)

    f.statuses.value = idle
    advanceTimeBy(61.seconds)
    runCurrent()
    assertEquals(listOf(FinishAction.ShutDown), f.actions.performed)
  }

  @Test
  fun briefDownloadDuringGrace_restartsTheCountdown() = runTest {
    val f = fixture()
    f.whenDone.choose(FinishAction.ShutDown)
    f.statuses.value = busy
    runCurrent()
    f.statuses.value = idle
    advanceTimeBy(59.seconds)
    runCurrent()

    f.statuses.value = busy
    runCurrent()
    f.statuses.value = idle
    advanceTimeBy(30.seconds)
    runCurrent()
    assertEquals(emptyList(), f.actions.performed)
    assertTrue(f.whenDone.pending)

    advanceTimeBy(31.seconds)
    runCurrent()
    assertEquals(listOf(FinishAction.ShutDown), f.actions.performed)
  }

  @Test
  fun serverOnly_neverCountsAsDownloading() = runTest {
    val f = fixture()
    f.whenDone.choose(FinishAction.Quit)
    f.statuses.value = ForegroundStatus(serverPort = 9000, discovering = 1)
    runCurrent()
    f.statuses.value = idle
    advanceTimeBy(120.seconds)
    runCurrent()
    assertEquals(emptyList(), f.actions.performed)
  }

  @Test
  fun choosingAgain_withdrawsAPendingWarning() = runTest {
    val f = fixture()
    f.whenDone.choose(FinishAction.ShutDown)
    f.statuses.value = busy
    runCurrent()
    f.statuses.value = idle
    runCurrent()

    f.whenDone.choose(FinishAction.Nothing)
    advanceTimeBy(120.seconds)
    runCurrent()
    assertEquals(emptyList(), f.actions.performed)
    assertTrue(f.messages.active.value.isEmpty())
  }

  @Test
  fun failure_isReported() = runTest {
    val f = fixture(RecordingActions(failure = IllegalStateException("refused")))
    f.whenDone.choose(FinishAction.Sleep)
    f.statuses.value = busy
    runCurrent()
    f.statuses.value = idle
    advanceTimeBy(61.seconds)
    runCurrent()

    val error = f.messages.active.value.single()
    assertEquals(MessageLevel.Error, error.level)
    assertEquals(FinishAction.Nothing, f.whenDone.action)
  }
}
