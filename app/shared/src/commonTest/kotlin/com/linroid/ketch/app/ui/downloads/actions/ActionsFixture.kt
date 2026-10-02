package com.linroid.ketch.app.ui.downloads.actions

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.platform.FileActions
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DeviceInfo
import com.linroid.ketch.app.state.LOCAL_DEVICE_ID
import com.linroid.ketch.app.state.RecordingKetchApi
import com.linroid.ketch.app.state.RecordingTask
import com.linroid.ketch.app.state.RowCapabilities
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.ui.list.RowCommands
import com.linroid.ketch.app.util.RowContext
import com.linroid.ketch.app.util.rowContent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.datetime.TimeZone
import kotlin.time.Instant

/** The embedded device of a test. */
internal val LocalDevice = DeviceInfo("This Mac", RowCapabilities.local())

/** A remote device of a test. */
internal val RemoteDevice = DeviceInfo("NAS", RowCapabilities.remote())

/**
 * An app with one recording device and a [RowActionRunner] over it, run by the test's scheduler.
 *
 * @param revealLabel what the platform calls Show in folder; `null` where files cannot be shown.
 * @param canTrash whether removed files can go to the Trash.
 */
internal class ActionsFixture(
  scope: TestScope,
  revealLabel: String? = "Show in Finder",
  canTrash: Boolean = false,
) {
  val api = RecordingKetchApi()
  val controller = AppController(
    instanceManager = InstanceManager(
      factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { api }),
    ),
    context = StandardTestDispatcher(scope.testScheduler),
  )
  val files = FakeFileActions(revealLabel, canTrash)
  val clipboard = FakeClipboard()
  val state: AppState get() = controller.state
  val runner =
    RowActionRunner(RowCommands(controller.state, files, clipboard, scope.backgroundScope) {})

  /** Adds a task in [state] to the device. */
  fun add(
    state: DownloadState,
    request: DownloadRequest = DownloadRequest("https://example.com/file.bin"),
  ): RecordingTask = api.add(state, request)

  /** Messages posted so far, oldest first. */
  fun messages(): List<AppMessage> = controller.messages.history.value.reversed()

  fun close() {
    controller.close()
  }
}

/** The row of [task] on [device], built like the task list builds one. */
internal fun rowOf(
  task: DownloadTask,
  device: DeviceInfo = LocalDevice,
  deviceId: String = LOCAL_DEVICE_ID,
): TaskRow {
  val request = task.requestState.value
  val state = task.state.value
  val context = RowContext(device, NOW, TimeZone.UTC)
  return TaskRow(
    key = TaskKey(deviceId, task.taskId),
    task = task,
    request = request,
    state = state,
    segments = task.segments.value,
    createdAt = task.createdAt,
    device = device,
    content = rowContent(request, state, task.createdAt, context),
  )
}

/** The fixed time of the tests. */
internal val NOW: Instant = Instant.parse("2026-10-01T12:00:00Z")

/** File actions that record their calls. */
internal class FakeFileActions(
  override val revealLabel: String?,
  override val canTrash: Boolean = false,
) : FileActions {
  val calls = mutableListOf<String>()

  /** Paths [exists] reports as gone. */
  val missing = mutableSetOf<String>()

  /** Paths [moveToTrash] refuses to move. */
  val refused = mutableSetOf<String>()

  override val canShare: Boolean = false

  override suspend fun open(path: String) {
    calls += "open $path"
  }

  override suspend fun reveal(path: String) {
    calls += "reveal $path"
  }

  override suspend fun share(path: String) {
    calls += "share $path"
  }

  override suspend fun exists(path: String): Boolean = path !in missing

  override suspend fun moveToTrash(path: String) {
    check(path !in refused) { "The Trash refused $path" }
    calls += "trash $path"
  }
}

/** A clipboard that records what was written to it. */
internal class FakeClipboard : SystemClipboard {
  val written = mutableListOf<String>()

  override val readsSilently: Boolean = true
  override val pasteEvents: Flow<String> = emptyFlow()

  override suspend fun hasLink(): Boolean = false

  override suspend fun readText(): String? = written.lastOrNull()

  override suspend fun writeText(text: String) {
    written += text
  }
}
