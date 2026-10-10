package com.linroid.ketch.app.state

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.PauseReason
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.backgroundChild
import com.linroid.ketch.app.feedback.ActivityEvent
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.fixtureTest
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.i18n.warmStrings
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.displayName
import com.linroid.ketch.app.testController
import com.linroid.ketch.app.util.TaskOrigin
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.IntakePreferences
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.UiPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

class AppStateCommandsTest {

  private val downloading = DownloadState.Downloading(RecordingTask.PROGRESS)
  private val paused = DownloadState.Paused(RecordingTask.PROGRESS)

  // Loading a string for the first time lets virtual time run, which would end Undo windows.
  @BeforeTest
  fun loadStrings() = runTest { warmStrings() }

  private fun TestScope.controller(api: KetchApi, configStore: ConfigStore? = null) =
    testController(api, configStore, context = backgroundChild())

  private fun commandsTest(
    api: RecordingKetchApi = RecordingKetchApi(),
    block: suspend TestScope.(RecordingKetchApi, AppController) -> Unit,
  ) = fixtureTest({ controller(api) }, AppController::close) { block(api, it) }

  private fun AppController.errors(): List<AppMessage> =
    messages.history.value.filter { it.level == MessageLevel.Error }

  private suspend fun AppController.click(label: String) {
    messages.active.value.last().actions.first { it.label.load() == label }.onClick()
  }

  @Test
  fun runTaskCommand_failure_postsOneErrorNamingTheDevice() = commandsTest { api, controller ->
    val task = api.add(downloading).apply { failure = IllegalStateException("Connection lost") }

    controller.state.runTaskCommand(task, "set speed limit", failure("set speed limit")) {
      setSpeedLimit(SpeedLimit.mbps(1))
    }
    runCurrent()

    val error = controller.errors().single()
    assertEquals("Couldn't set speed limit on This Mac", error.title.load())
    assertEquals(TaskKey(LOCAL_DEVICE_ID, task.taskId), error.taskKey)
    assertIs<IllegalStateException>(error.cause)
  }

  @Test
  fun runTaskCommand_afterAFailure_scopeStillRunsCommands() = commandsTest { api, controller ->
    val failing = api.add(downloading).apply { failure = IllegalStateException("Server said no") }
    val other = api.add(downloading)

    controller.state.runTaskCommand(failing, "pause", failure("pause")) { pause() }
    runCurrent()
    controller.state.runTaskCommand(other, "pause", failure("pause")) { pause() }
    runCurrent()

    assertEquals(listOf("pause"), other.calls)
    assertIs<DownloadState.Paused>(other.state.value)
  }

  @Test
  fun runTaskCommand_cancelled_postsNothing() = commandsTest { api, controller ->
    val task = api.add(downloading)

    val command = controller.state.runTaskCommand(task, "pause", failure("pause")) {
      awaitCancellation()
    }
    runCurrent()
    command.cancel()
    runCurrent()

    assertTrue(controller.errors().isEmpty())
    assertTrue(controller.state.pending.value.isEmpty())
  }

  @Test
  fun runTaskCommand_callThrowsCancellation_postsOneError() = commandsTest { api, controller ->
    val task = api.add(downloading).apply { failure = CancellationException("Client closed") }

    controller.state.runTaskCommand(task, "pause", failure("pause")) { pause() }
    runCurrent()

    assertEquals("Couldn't pause on This Mac", controller.errors().single().title.load())
  }

  @Test
  fun runTaskCommand_inFlight_isPending() = commandsTest { api, controller ->
    val task = api.add(downloading)
    val gate = CompletableDeferred<Unit>()

    controller.state.runTaskCommand(task, "pause", failure("pause")) {
      gate.await()
      pause()
    }
    runCurrent()
    val key = TaskKey(LOCAL_DEVICE_ID, task.taskId)
    assertEquals(setOf(key to "pause"), controller.state.pending.value)

    gate.complete(Unit)
    runCurrent()
    assertTrue(controller.state.pending.value.isEmpty())
  }

  @Test
  fun pauseAll_queueWouldPromote_leavesNothingDownloading() =
    commandsTest(RecordingKetchApi(maxActive = 2)) { api, controller ->
      val running = List(2) { api.add(downloading) }
      val queued = List(3) { api.add(DownloadState.Queued) }
      val done = api.add(DownloadState.Completed("/downloads/done.iso"))

      controller.state.pauseAll()
      runCurrent()

      assertTrue(api.tasks.value.none { it.state.value is DownloadState.Downloading })
      assertTrue((running + queued).all { it.state.value is DownloadState.Paused })
      assertTrue(done.calls.isEmpty())
      assertEquals("Paused 5 downloads", controller.messages.active.value.last().title.load())
      assertTrue(controller.messages.history.value.isEmpty())
      assertEquals(0, controller.messages.unreadCount.value)
    }

  @Test
  fun pauseAll_oneTaskFails_pausesTheOthersAndReportsIt() = commandsTest { api, controller ->
    val failing = api.add(downloading).apply { failure = CancellationException("Client closed") }
    val others = List(2) { api.add(downloading) }

    controller.state.pauseAll()
    runCurrent()

    assertTrue(others.all { it.state.value is DownloadState.Paused })
    assertEquals(listOf("pause"), failing.calls)
    val toast = controller.messages.active.value.last()
    assertEquals(MessageLevel.Warning, toast.level)
    assertEquals("Paused 2 downloads · 1 failed", toast.title.load())
    assertEquals(listOf(toast), controller.messages.history.value)
  }

  @Test
  fun pauseAll_nothingPaused_leavesUndoToTheEarlierOperation() = commandsTest { api, controller ->
    val removed = api.add(DownloadState.Completed("/downloads/a.iso"))
    api.add(downloading).apply { failure = IllegalStateException("Server said no") }

    controller.state.remove(listOf(removed))
    controller.state.pauseAll()
    runCurrent()

    assertEquals(
      listOf("Undo remove"),
      controller.state.pendingOps.ops.value.map { it.undoTitle }.load(),
    )
    assertEquals(1, controller.errors().size)
    assertTrue(controller.state.pendingOps.undoLast())
  }

  @Test
  fun pauseAll_undo_resumesExactlyThePausedTasks() = commandsTest { api, controller ->
    val running = api.add(downloading)
    val queued = api.add(DownloadState.Queued)
    val alreadyPaused = api.add(paused)

    controller.state.pauseAll()
    runCurrent()
    controller.click("Undo")
    runCurrent()

    assertEquals(listOf("pause", "resume"), running.calls)
    assertEquals(listOf("pause", "resume"), queued.calls)
    assertTrue(alreadyPaused.calls.isEmpty())
  }

  @Test
  fun retryFailed_errorWithNothingToResume_downloadsAgain() = commandsTest { api, controller ->
    val dropped = api.add(DownloadState.Failed(KetchError.Network()))
    val changed = api.add(DownloadState.Failed(KetchError.FileChanged("ETag changed")))
    val refused = api.add(DownloadState.Failed(KetchError.Http(416)))
    val unsupported = api.add(DownloadState.Failed(KetchError.Unsupported()))

    controller.state.retryFailed()
    runCurrent()

    assertEquals(listOf("resume"), dropped.calls)
    listOf(changed, refused, unsupported).forEach {
      assertEquals(listOf("remove deleteFiles=true"), it.calls)
    }
    assertEquals(
      listOf(changed, refused, unsupported).map { it.request.url },
      api.requests.map { it.url },
    )
  }

  @Test
  fun retry_eachState_resumesOrStartsOver() = commandsTest { api, controller ->
    val dropped = api.add(DownloadState.Failed(KetchError.Network()))
    val changed = api.add(DownloadState.Failed(KetchError.FileChanged("ETag changed")))
    val canceled = api.add(DownloadState.Canceled)
    val waiting = api.add(paused)
    val running = api.add(downloading)

    listOf(dropped, changed, canceled, waiting, running).forEach { controller.state.retry(it) }
    runCurrent()

    assertEquals(listOf("resume"), dropped.calls)
    assertEquals(listOf("resume"), waiting.calls)
    assertEquals(listOf("remove deleteFiles=true"), changed.calls)
    assertEquals(listOf("remove deleteFiles=true"), canceled.calls)
    assertTrue(running.calls.isEmpty())
    assertEquals(listOf(changed, canceled).map { it.request.url }, api.requests.map { it.url })
  }

  @Test
  fun redownload_delayedRequest_startsAtOnce() = commandsTest { api, controller ->
    val request = DownloadRequest(
      url = "https://example.com/a.iso",
      schedule = DownloadSchedule.AfterDelay(30.minutes),
    )
    val canceled = api.add(DownloadState.Canceled, request)

    controller.state.redownload(canceled)
    runCurrent()

    assertEquals(listOf("remove deleteFiles=true"), canceled.calls)
    val again = api.requests.single()
    assertEquals(request.url, again.url)
    assertEquals(DownloadSchedule.Immediate, again.schedule)
  }

  @Test
  fun remove_hidesRowsAtOnceAndUndoRestoresThem() = commandsTest { api, controller ->
    val state = controller.state
    val first = api.add(DownloadState.Completed("/downloads/a.iso"))
    val second = api.add(downloading)
    backgroundScope.launch { state.tasks.collect {} }
    runCurrent()

    state.remove(listOf(first))
    runCurrent()
    assertEquals(listOf(second), state.tasks.value)

    controller.click("Undo")
    advanceTimeBy(10.seconds)
    runCurrent()
    assertEquals(listOf(first, second), state.tasks.value)
    assertTrue(first.calls.isEmpty())
  }

  @Test
  fun remove_windowEnds_removesTheTaskAfterSixSeconds() = commandsTest { api, controller ->
    val task = api.add(DownloadState.Completed("/downloads/a.iso"))

    controller.state.remove(listOf(task))
    advanceTimeBy(5.seconds)
    runCurrent()
    assertTrue(task.calls.isEmpty())

    advanceTimeBy(1001.milliseconds)
    runCurrent()
    assertEquals(listOf("remove deleteFiles=false"), task.calls)
    assertTrue(api.tasks.value.isEmpty())
  }

  @Test
  fun remove_appCloses_commitsAtOnce() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)
    val task = api.add(DownloadState.Completed("/downloads/a.iso"))

    controller.state.remove(listOf(task), deleteFiles = true)
    runCurrent()
    controller.close()
    runCurrent()

    assertEquals(listOf("remove deleteFiles=true"), task.calls)
  }

  @Test
  fun sendTo_copy_keepsTheTaskOptionsAndTheSource() = commandsTest { api, controller ->
    val nas = RecordingKetchApi("NAS")
    val request = DownloadRequest(
      url = "https://example.com/ubuntu.iso",
      destination = Destination("/Users/me/Downloads/ubuntu.iso"),
      connections = 8,
      headers = mapOf("Cookie" to "session=1"),
      speedLimit = SpeedLimit.kbps(512),
      priority = DownloadPriority.HIGH,
    )
    val task = api.add(paused, request)

    controller.state.sendTo(listOf(task), EmbeddedInstance(nas, "NAS"))
    advanceTimeBy(10.seconds)
    runCurrent()

    val sent = nas.requests.single()
    assertEquals(request.headers, sent.headers)
    assertEquals(request.speedLimit, sent.speedLimit)
    assertEquals(request.priority, sent.priority)
    assertEquals(request.connections, sent.connections)
    assertEquals(Destination("ubuntu.iso"), sent.destination)
    assertTrue(task.calls.isEmpty())
    assertEquals("Sent ubuntu.iso to NAS", controller.messages.active.value.last().title.load())
  }

  @Test
  fun sendTo_moveAndAdded_removesTheSourceWhenTheWindowEnds() = commandsTest { api, controller ->
    val nas = RecordingKetchApi("NAS")
    val task = api.add(paused)
    val key = TaskKey(LOCAL_DEVICE_ID, task.taskId)
    controller.state.selectedKeys = setOf(key)
    controller.state.inspect(key)

    controller.state.sendTo(listOf(task), EmbeddedInstance(nas, "NAS"), move = true)
    runCurrent()
    assertEquals(setOf(key), controller.state.pendingOps.hidden.value)
    assertEquals(emptySet(), controller.state.selectedKeys)
    assertEquals(null, controller.state.inspectedTask)

    advanceTimeBy(7.seconds)
    runCurrent()
    // The paused source's partial file goes with it; the copy starts over on the NAS.
    assertEquals(listOf("remove deleteFiles=true"), task.calls)
  }

  @Test
  fun sendTo_moveButAddFailed_keepsTheSource() = commandsTest { api, controller ->
    val nas = RecordingKetchApi("NAS").apply {
      downloadFailure = { IllegalStateException("Connection refused") }
    }
    val task = api.add(paused)

    controller.state.sendTo(listOf(task), EmbeddedInstance(nas, "NAS"), move = true)
    advanceTimeBy(10.seconds)
    runCurrent()

    assertTrue(task.calls.isEmpty())
    assertTrue(controller.state.pendingOps.hidden.value.isEmpty())
    assertEquals("Couldn't send file1.bin to NAS", controller.errors().single().title.load())
  }

  @Test
  fun startNow_noFreeSlot_namesThePreemptedTaskAndUndoRestoresIt() =
    commandsTest(RecordingKetchApi(maxActive = 1)) { api, controller ->
      val running = api.add(downloading, DownloadRequest("https://example.com/debian.iso"))
      val waiting =
        api.add(DownloadState.Queued, DownloadRequest("https://example.com/blender.dmg"))

      controller.state.startNow(waiting)
      runCurrent()
      assertEquals(
        "Started blender.dmg now · paused debian.iso to make room",
        controller.messages.active.value.last().title.load(),
      )
      assertIs<DownloadState.Downloading>(waiting.state.value)
      assertEquals(
        DownloadState.Paused(RecordingTask.PROGRESS, PauseReason.Preempted(waiting.taskId)),
        running.state.value,
      )

      controller.click("Undo")
      runCurrent()
      assertEquals(DownloadPriority.NORMAL, waiting.request.priority)
      assertIs<DownloadState.Downloading>(running.state.value)
      assertIs<DownloadState.Queued>(waiting.state.value)
    }

  @Test
  fun startNow_noFreeSlotOnOlderDevice_namesThePreemptedTaskAndUndoRestoresIt() =
    commandsTest(RecordingKetchApi(maxActive = 1, preemptsToPaused = false)) { api, controller ->
      val running = api.add(downloading, DownloadRequest("https://example.com/debian.iso"))
      val waiting =
        api.add(DownloadState.Queued, DownloadRequest("https://example.com/blender.dmg"))

      controller.state.startNow(waiting)
      runCurrent()
      assertEquals(
        "Started blender.dmg now · paused debian.iso to make room",
        controller.messages.active.value.last().title.load(),
      )
      assertIs<DownloadState.Queued>(running.state.value)

      controller.click("Undo")
      runCurrent()
      assertIs<DownloadState.Downloading>(running.state.value)
      assertIs<DownloadState.Queued>(waiting.state.value)
    }

  @Test
  fun resumeAll_skipsPreemptedTasks() = commandsTest { api, controller ->
    val reason = PauseReason.Preempted("urgent")
    val preempted = api.add(DownloadState.Paused(RecordingTask.PROGRESS, reason))
    val byUser = api.add(paused)

    controller.state.resumeAll().join()
    runCurrent()

    assertEquals(emptyList(), preempted.calls)
    assertEquals(listOf("resume"), byUser.calls)
  }

  @Test
  fun quickAdd_link_usesTheOptionsLastUsedOnTheDevice() = runTest {
    val api = RecordingKetchApi()
    val defaults = IntakePreferences(
      folder = "/data/downloads",
      priority = DownloadPriority.HIGH,
      connections = 4,
    )
    val store = RecordingConfigStore(
      KetchConfig(ui = UiPreferences(intake = mapOf(LOCAL_DEVICE_ID to defaults))),
    )
    val controller = controller(api, store)

    controller.state.quickAdd(listOf("https://example.com/ubuntu.iso"))
    runCurrent()

    val request = api.requests.single()
    assertEquals(Destination("/data/downloads/"), request.destination)
    assertEquals(DownloadPriority.HIGH, request.priority)
    assertEquals(4, request.connections)
    assertEquals(TaskOrigin.App, TaskOrigin.of(request))
    val toast = controller.messages.active.value.last()
    val device = checkNotNull(controller.state.activeInstance.value).displayName.load()
    assertEquals("Added ubuntu.iso → $device", toast.title.load())
    assertEquals(listOf("Options", "Undo"), toast.actions.map { it.label }.load())
    controller.close()
  }

  @Test
  fun quickAdd_undo_removesTheTaskAndItsFile() = commandsTest { api, controller ->
    controller.state.quickAdd(listOf("https://example.com/ubuntu.iso"))
    runCurrent()
    val task = api.tasks.value.single() as RecordingTask
    controller.click("Undo")
    runCurrent()

    assertEquals(listOf("remove deleteFiles=true"), task.calls)
  }

  @Test
  fun quickAdd_undoThatFails_saysSo() = commandsTest { api, controller ->
    controller.state.quickAdd(listOf("https://example.com/ubuntu.iso"))
    runCurrent()
    val task = api.tasks.value.single() as RecordingTask
    task.failure = IllegalStateException("file in use")
    controller.click("Undo")
    runCurrent()

    val error = controller.messages.active.value.last()
    assertEquals(MessageLevel.Error, error.level)
    assertEquals("Couldn't remove ubuntu.iso on This Mac", error.title.load())
    assertEquals("file in use", error.detail?.load())
  }

  @Test
  fun quickAdd_magnet_doesNotAwaitFiles() = commandsTest { api, controller ->
    controller.state.quickAdd(listOf(MAGNET))
    runCurrent()

    assertFalse(api.requests.single().awaitFileSelection)
  }

  @Test
  fun report_filesNeeded_offersToChooseThem() = commandsTest { api, controller ->
    val waiting = DownloadState.Paused(RecordingTask.PROGRESS, PauseReason.AwaitingFileSelection)
    val task = api.add(waiting, DownloadRequest(MAGNET))
    val key = controller.state.keyOf(task)

    controller.state.report(ActivityEvent.FilesNeeded(key, task.request))
    val message = controller.messages.active.value.last()
    controller.click("Choose files…")

    assertEquals(MessageLevel.Info, message.level)
    assertEquals("Choose which files of “Show” to download", message.title.load())
    assertEquals(key, controller.state.inspectedTask)
    assertEquals(key, controller.state.filesRequest)
    controller.state.filesRequestHandled()
    assertNull(controller.state.filesRequest)
  }

  private companion object {
    const val MAGNET = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Show"
  }
}

// A command's error title for the device's name: "Couldn't {what} on {device}".
private fun failure(what: String): (UiText) -> UiText =
  { device -> listOf(verbatim("Couldn't $what on "), device).joinText(separator = "") }
