package com.linroid.ketch.app.ui.list

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.DebouncedCommit
import com.linroid.ketch.app.components.parseSpeedInput
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.warmStrings
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.RecordingTask
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.SpeedUnit
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.rowOf
import com.linroid.ketch.app.ui.downloads.actions.ActionsFixture
import com.linroid.ketch.app.ui.downloads.actions.RowActionRunner
import com.linroid.ketch.app.ui.downloads.actions.actionsTest
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class RowCommandsTest {
  private val downloading = DownloadState.Downloading(RecordingTask.PROGRESS)

  private fun ActionsFixture.errors(): List<AppMessage> =
    messages().filter { it.level == MessageLevel.Error }

  @Test
  fun run_downloadAgainOnCanceledRow_addsANewTaskAndRemovesTheOld() = actionsTest { f ->
    val task = f.api.add(DownloadState.Canceled)

    f.commands.run(RowAction.DownloadAgain, rowOf(task))
    runCurrent()

    assertEquals(listOf(task.request.url), f.api.requests.map { it.url })
    assertFalse(task in f.api.tasks.value)
    assertEquals(1, f.api.tasks.value.size)
  }

  @Test
  fun run_pauseFails_postsOneErrorNamingTheTaskAndDevice() = actionsTest { f ->
    val task = f.api.add(downloading).apply { failure = IllegalStateException("Connection lost") }

    f.commands.run(RowAction.Pause, rowOf(task))
    runCurrent()

    val error = f.errors().single()
    assertEquals("Couldn't pause ${rowOf(task).name} on This Mac", error.title.load())
  }

  @Test
  fun run_retryWithOneConnection_lowersConnectionsThenResumes() = actionsTest { f ->
    val task = f.api.add(DownloadState.Failed(KetchError.Http(429)))

    f.commands.run(RowAction.RetryWithConnections(1), rowOf(task))
    runCurrent()

    assertEquals(listOf("connections 1", "resume"), task.calls)
  }

  @Test
  fun run_remove_hidesTheRowUntilTheUndoWindowEnds() = actionsTest { f ->
    val task = f.api.add(DownloadState.Completed("/downloads/a.iso", totalBytes = 10))
    backgroundScope.launch { f.controller.state.tasks.collect {} }
    runCurrent()

    f.commands.run(RowAction.Remove, rowOf(task))
    runCurrent()

    assertTrue(f.controller.state.tasks.value.isEmpty())
    assertTrue(task.calls.isEmpty())
    val toast = f.controller.messages.active.value.last()
    assertTrue(toast.actions.any { it.label.load() == "Undo" })
    advanceTimeBy(7.seconds)
    runCurrent()
    assertEquals(listOf("remove deleteFiles=false"), task.calls)
  }

  @Test
  fun run_open_opensTheSavedFile() = actionsTest { f ->
    val task = f.api.add(DownloadState.Completed("/downloads/a.iso", totalBytes = 10))

    f.commands.run(RowAction.Open, rowOf(task))
    runCurrent()

    assertEquals(listOf("open /downloads/a.iso"), f.files.calls)
  }

  @Test
  fun run_copyLink_putsTheLinkOnTheClipboardAndConfirms() = actionsTest { f ->
    val task = f.api.add(downloading)

    f.commands.run(RowAction.CopyLink, rowOf(task))
    runCurrent()

    assertEquals(listOf(task.request.url), f.clipboard.written)
    assertEquals("Copied link", f.controller.messages.history.value.first().title.load())
  }

  @Test
  fun run_copyError_putsTheErrorsTitleAndHintOnTheClipboard() = actionsTest { f ->
    // The copy reads the error's text while virtual time runs.
    warmStrings()
    val task = f.api.add(DownloadState.Failed(KetchError.Http(404)))

    f.commands.run(RowAction.CopyError, rowOf(task))
    runCurrent()

    assertEquals(
      listOf("File not found (404)\nThe server no longer has this file."),
      f.clipboard.written,
    )
    assertEquals("Copied error", f.controller.messages.history.value.first().title.load())
  }

  @Test
  fun run_editLink_opensTheAddSheetToRetryTheTask() = actionsTest { f ->
    val task = f.api.add(DownloadState.Failed(KetchError.Http(403)))
    runCurrent()

    f.commands.run(RowAction.EditLink, rowOf(task))

    val request = assertNotNull(f.controller.state.intakeRequest)
    assertEquals(task.request.url, request.seeds.single().url)
    assertEquals(TaskKey(LOCAL_DEVICE_ID, task.taskId), request.retryOf)
  }

  @Test
  fun run_openSourcePage_opensTheReferer() = runTest {
    val opened = mutableListOf<String>()
    val f = ActionsFixture(this, openUri = { opened += it })
    val task = f.api.add(DownloadState.Failed(KetchError.Http(403)), capturedOn(PAGE))

    f.commands.run(RowAction.OpenSourcePage, rowOf(task))

    assertEquals(listOf(PAGE), opened)
    f.close()
  }

  @Test
  fun run_openSourcePageThrows_postsAnErrorInsteadOfCrashing() =
    actionsTest(openUri = { throw IllegalArgumentException("No browser") }) { f ->
      val task = f.api.add(DownloadState.Failed(KetchError.Http(403)), capturedOn(PAGE))

      f.commands.run(RowAction.OpenSourcePage, rowOf(task))

      assertEquals("Couldn't open the source page", f.errors().single().title.load())
    }

  @Test
  fun canRun_refererOfAnotherScheme_refusesToOpenIt() = actionsTest { f ->
    val task = f.api.add(
      DownloadState.Failed(KetchError.Http(403)),
      capturedOn("android-app://com.example.app"),
    )

    assertFalse(f.commands.canRun(RowAction.OpenSourcePage, rowOf(task)))
    assertFalse(RowAction.OpenSourcePage in RowActionRunner(f.commands).menu(rowOf(task)))
  }

  @Test
  fun setSpeedLimit_valueTypedKeyByKey_sendsOneSetSpeedLimit() = actionsTest { f ->
    val task = f.api.add(downloading)
    val row = rowOf(task)
    // The list's speed field debounces what is typed the way SpeedLimitPicker does.
    val field = DebouncedCommit<SpeedLimit>(backgroundScope, 600.milliseconds) {
      f.commands.setSpeedLimit(row, it)
    }

    listOf("5", "50", "500").forEach { text ->
      field.update(assertNotNull(parseSpeedInput(text, SpeedUnit.KB)))
      advanceTimeBy(200.milliseconds)
    }
    advanceTimeBy(1.seconds)
    runCurrent()

    assertEquals(listOf("speed"), task.calls)
    assertEquals(SpeedLimit.kbps(500), task.request.speedLimit)
  }

  @Test
  fun canRun_withoutAClipboard_refusesCopies() = actionsTest { f ->
    val commands = RowCommands(f.controller.state, f.files, null, backgroundScope) {}
    val task = f.api.add(downloading)

    assertFalse(commands.canRun(RowAction.CopyLink, rowOf(task)))
    assertNull(RowActionRunner(commands).menu(rowOf(task)).firstOrNull { it == RowAction.CopyLink })
  }

  @Test
  fun isBusy_pauseInFlight_isBusy() {
    val row = ListFixtures.row("a", downloading)

    assertTrue(RowCommands.isBusy(row, setOf(row.key to TaskCommand.Pause.key)))
  }

  @Test
  fun isBusy_onlyASettingChangeInFlight_isNotBusy() {
    val row = ListFixtures.row("a", downloading)

    assertFalse(RowCommands.isBusy(row, setOf(row.key to RowCommands.speedLimitLabel(row))))
  }

  private fun capturedOn(page: String): DownloadRequest = DownloadRequest(
    url = "https://cdn.example.com/a.iso",
    headers = mapOf("Referer" to page),
  )

  private companion object {
    const val PAGE = "https://example.com/releases"
  }
}
