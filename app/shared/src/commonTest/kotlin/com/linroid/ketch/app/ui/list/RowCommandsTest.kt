package com.linroid.ketch.app.ui.list

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.platform.FileActions
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.DeviceInfo
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.ListFixtures
import com.linroid.ketch.app.state.RecordingKetchApi
import com.linroid.ketch.app.state.RecordingTask
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.RowCapabilities
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.util.RowContext
import com.linroid.ketch.app.util.rowContent
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class RowCommandsTest {

  private val local = DeviceInfo("This Mac", RowCapabilities.local())
  private val remote = DeviceInfo("NAS", RowCapabilities.remote())
  private val downloading = DownloadState.Downloading(RecordingTask.PROGRESS)

  private class Fixture(
    val api: RecordingKetchApi,
    val controller: AppController,
    val files: RecordingFileActions,
    val clipboard: RecordingClipboard,
    val commands: RowCommands,
  ) {
    fun errors(): List<AppMessage> =
      controller.messages.history.value.filter { it.level == MessageLevel.Error }
  }

  private fun TestScope.fixture(revealLabel: String? = "Show in Finder"): Fixture {
    val api = RecordingKetchApi()
    val controller = AppController(
      instanceManager = InstanceManager(
        factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { api }),
      ),
      context = StandardTestDispatcher(testScheduler),
    )
    val files = RecordingFileActions(revealLabel)
    val clipboard = RecordingClipboard()
    val commands = RowCommands(controller.state, files, clipboard, backgroundScope) {}
    return Fixture(api, controller, files, clipboard, commands)
  }

  private fun rowOf(task: DownloadTask, device: DeviceInfo = local): TaskRow {
    val request = task.requestState.value
    val state = task.state.value
    val context = RowContext(device, NOW, TimeZone.UTC)
    return TaskRow(
      key = TaskKey(LOCAL_DEVICE_ID, task.taskId),
      task = task,
      request = request,
      state = state,
      segments = emptyList(),
      createdAt = task.createdAt,
      device = device,
      content = rowContent(request, state, task.createdAt, context),
    )
  }

  @Test
  fun trailing_canceledRow_isDownloadAgain() = runTest {
    val f = fixture()
    val task = f.api.add(DownloadState.Canceled)

    assertEquals(RowAction.DownloadAgain, f.commands.trailing(rowOf(task)))
    f.controller.close()
  }

  @Test
  fun trailing_failedRow_isTheErrorCopyPrimary() = runTest {
    val f = fixture()
    val task = f.api.add(DownloadState.Failed(KetchError.Http(403)))

    assertEquals(RowAction.EditLink, f.commands.trailing(rowOf(task)))
    f.controller.close()
  }

  @Test
  fun trailing_diskErrorWhereFilesCannotBeRevealed_fallsBackToRetry() = runTest {
    val f = fixture(revealLabel = null)
    val request = DownloadRequest("https://example.com/a.iso", Destination("/downloads/"))
    val task = f.api.add(DownloadState.Failed(KetchError.Disk()), request)

    assertEquals(RowAction.Retry, f.commands.trailing(rowOf(task)))
    f.controller.close()
  }

  @Test
  fun menu_remoteRow_offersNoStartLater() = runTest {
    val f = fixture()
    val task = f.api.add(DownloadState.Queued)

    val menu = f.commands.menu(rowOf(task, remote))

    assertFalse(RowAction.StartLater in menu)
    assertTrue(RowAction.SpeedLimit in menu)
    f.controller.close()
  }

  @Test
  fun menu_localRow_offersStartLater() = runTest {
    val f = fixture()
    val task = f.api.add(DownloadState.Queued)

    assertTrue(RowAction.StartLater in f.commands.menu(rowOf(task)))
    f.controller.close()
  }

  @Test
  fun run_downloadAgainOnCanceledRow_addsANewTaskAndRemovesTheOld() = runTest {
    val f = fixture()
    val task = f.api.add(DownloadState.Canceled)

    f.commands.run(RowAction.DownloadAgain, rowOf(task))
    runCurrent()

    assertEquals(listOf(task.request.url), f.api.requests.map { it.url })
    assertFalse(task in f.api.tasks.value)
    assertEquals(1, f.api.tasks.value.size)
    f.controller.close()
  }

  @Test
  fun run_pauseFails_postsOneErrorNamingTheTaskAndDevice() = runTest {
    val f = fixture()
    val task = f.api.add(downloading).apply { failure = IllegalStateException("Connection lost") }

    f.commands.run(RowAction.Pause, rowOf(task))
    runCurrent()

    val error = f.errors().single()
    assertEquals("Couldn't pause ${rowOf(task).name} on This Mac", error.title)
    f.controller.close()
  }

  @Test
  fun run_retryWithOneConnection_lowersConnectionsThenResumes() = runTest {
    val f = fixture()
    val task = f.api.add(DownloadState.Failed(KetchError.Http(429)))

    f.commands.run(RowAction.RetryWithConnections(1), rowOf(task))
    runCurrent()

    assertEquals(listOf("connections 1", "resume"), task.calls)
    f.controller.close()
  }

  @Test
  fun run_remove_hidesTheRowUntilTheUndoWindowEnds() = runTest {
    val f = fixture()
    val task = f.api.add(DownloadState.Completed("/downloads/a.iso", totalBytes = 10))
    backgroundScope.launch { f.controller.state.tasks.collect {} }
    runCurrent()

    f.commands.run(RowAction.Remove, rowOf(task))
    runCurrent()

    assertTrue(f.controller.state.tasks.value.isEmpty())
    assertTrue(task.calls.isEmpty())
    val toast = f.controller.messages.active.value.last()
    assertTrue(toast.actions.any { it.label == "Undo" })
    advanceTimeBy(7.seconds)
    runCurrent()
    assertEquals(listOf("remove deleteFiles=false"), task.calls)
    f.controller.close()
  }

  @Test
  fun run_open_opensTheSavedFile() = runTest {
    val f = fixture()
    val task = f.api.add(DownloadState.Completed("/downloads/a.iso", totalBytes = 10))

    f.commands.run(RowAction.Open, rowOf(task))
    runCurrent()

    assertEquals(listOf("open /downloads/a.iso"), f.files.calls)
    f.controller.close()
  }

  @Test
  fun run_copyLink_putsTheLinkOnTheClipboardAndConfirms() = runTest {
    val f = fixture()
    val task = f.api.add(downloading)

    f.commands.run(RowAction.CopyLink, rowOf(task))
    runCurrent()

    assertEquals(listOf(task.request.url), f.clipboard.written)
    assertEquals("Copied link", f.controller.messages.history.value.first().title)
    f.controller.close()
  }

  @Test
  fun run_editLink_opensTheAddSheetToRetryTheTask() = runTest {
    val f = fixture()
    val task = f.api.add(DownloadState.Failed(KetchError.Http(403)))
    runCurrent()

    f.commands.run(RowAction.EditLink, rowOf(task))

    val request = assertNotNull(f.controller.state.intakeRequest)
    assertEquals(task.request.url, request.seeds.single().url)
    assertEquals(TaskKey(LOCAL_DEVICE_ID, task.taskId), request.retryOf)
    f.controller.close()
  }

  @Test
  fun canRun_withoutAClipboard_refusesCopies() = runTest {
    val f = fixture()
    val commands = RowCommands(f.controller.state, f.files, null, backgroundScope) {}
    val task = f.api.add(downloading)

    assertFalse(commands.canRun(RowAction.CopyLink, rowOf(task)))
    assertNull(commands.menu(rowOf(task)).firstOrNull { it == RowAction.CopyLink })
    f.controller.close()
  }

  @Test
  fun isBusy_pauseInFlight_isBusy() {
    val row = ListFixtures.row("a", downloading)

    assertTrue(RowCommands.isBusy(row, setOf(row.key to "pause ${row.name}")))
  }

  @Test
  fun isBusy_onlyASettingChangeInFlight_isNotBusy() {
    val row = ListFixtures.row("a", downloading)

    assertFalse(RowCommands.isBusy(row, setOf(row.key to RowCommands.speedLimitLabel(row))))
  }

  private companion object {
    val NOW: Instant = Instant.parse("2026-10-01T12:00:00Z")
  }
}

/** File actions that record what they were asked to do. */
internal class RecordingFileActions(override val revealLabel: String?) : FileActions {
  val calls = mutableListOf<String>()

  override val canShare: Boolean = false
  override val canTrash: Boolean = false

  override suspend fun open(path: String) {
    calls += "open $path"
  }

  override suspend fun reveal(path: String) {
    calls += "reveal $path"
  }

  override suspend fun share(path: String) {
    calls += "share $path"
  }

  override suspend fun exists(path: String): Boolean = true

  override suspend fun moveToTrash(path: String) {
    calls += "trash $path"
  }
}

/** A clipboard that records what was written to it. */
internal class RecordingClipboard : SystemClipboard {
  val written = mutableListOf<String>()

  override val readsSilently: Boolean = true
  override val pasteEvents: Flow<String> = emptyFlow()

  override suspend fun hasLink(): Boolean = false

  override suspend fun readText(): String? = written.lastOrNull()

  override suspend fun writeText(text: String) {
    written += text
  }
}
