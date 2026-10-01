package com.linroid.ketch.app.state

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.IntakePreferences
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.UiPreferences
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AppStateCommandsTest {

  private val downloading = DownloadState.Downloading(RecordingTask.PROGRESS)
  private val paused = DownloadState.Paused(RecordingTask.PROGRESS)

  private fun TestScope.controller(
    api: KetchApi,
    configStore: ConfigStore? = null,
  ): AppController = AppController(
    instanceManager = InstanceManager(
      factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { api }),
      configStore = configStore,
    ),
    context = StandardTestDispatcher(testScheduler),
  )

  private fun AppController.errors(): List<AppMessage> =
    messages.history.value.filter { it.level == MessageLevel.Error }

  private fun AppController.click(label: String) {
    messages.active.value.last().actions.first { it.label == label }.onClick()
  }

  @Test
  fun runTaskCommand_failure_postsOneErrorNamingTheDevice() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)
    val task = api.add(downloading).apply { failure = IllegalStateException("Connection lost") }

    controller.state.runTaskCommand(task, "set speed limit") { setSpeedLimit(SpeedLimit.mbps(1)) }
    runCurrent()

    val error = controller.errors().single()
    assertEquals("Couldn't set speed limit on This Mac", error.title)
    assertEquals(TaskKey(LOCAL_DEVICE_ID, task.taskId), error.taskKey)
    assertIs<IllegalStateException>(error.cause)
    controller.close()
  }

  @Test
  fun runTaskCommand_afterAFailure_scopeStillRunsCommands() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)
    val failing = api.add(downloading).apply { failure = IllegalStateException("Server said no") }
    val other = api.add(downloading)

    controller.state.runTaskCommand(failing, "pause") { pause() }
    runCurrent()
    controller.state.runTaskCommand(other, "pause") { pause() }
    runCurrent()

    assertEquals(listOf("pause"), other.calls)
    assertIs<DownloadState.Paused>(other.state.value)
    controller.close()
  }

  @Test
  fun runTaskCommand_cancelled_postsNothing() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)
    val task = api.add(downloading)

    val command = controller.state.runTaskCommand(task, "pause") { awaitCancellation() }
    runCurrent()
    command.cancel()
    runCurrent()

    assertTrue(controller.errors().isEmpty())
    assertTrue(controller.state.pending.value.isEmpty())
    controller.close()
  }

  @Test
  fun runTaskCommand_callThrowsCancellation_postsOneError() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)
    val task = api.add(downloading).apply { failure = CancellationException("Client closed") }

    controller.state.runTaskCommand(task, "pause") { pause() }
    runCurrent()

    assertEquals("Couldn't pause on This Mac", controller.errors().single().title)
    controller.close()
  }

  @Suppress("DEPRECATION")
  @Test
  fun dismissError_severalErrors_clearsTheBanner() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)
    val tasks = List(2) { api.add(downloading).apply { failure = IllegalStateException("No") } }

    tasks.forEach { controller.state.runTaskCommand(it, "pause") { pause() } }
    runCurrent()
    assertEquals("Couldn't pause on This Mac: No", controller.state.errorMessage)
    controller.state.dismissError()
    runCurrent()

    assertEquals(null, controller.state.errorMessage)
    assertEquals(2, controller.errors().size)
    controller.close()
  }

  @Test
  fun runTaskCommand_inFlight_isPending() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)
    val task = api.add(downloading)
    val gate = CompletableDeferred<Unit>()

    controller.state.runTaskCommand(task, "pause") {
      gate.await()
      pause()
    }
    runCurrent()
    val key = TaskKey(LOCAL_DEVICE_ID, task.taskId)
    assertEquals(setOf(key to "pause"), controller.state.pending.value)

    gate.complete(Unit)
    runCurrent()
    assertTrue(controller.state.pending.value.isEmpty())
    controller.close()
  }

  @Test
  fun pauseAll_queueWouldPromote_leavesNothingDownloading() = runTest {
    val api = RecordingKetchApi(maxActive = 2)
    val controller = controller(api)
    val running = List(2) { api.add(downloading) }
    val queued = List(3) { api.add(DownloadState.Queued) }
    val done = api.add(DownloadState.Completed("/downloads/done.iso"))

    controller.state.pauseAll()
    runCurrent()

    assertTrue(api.tasks.value.none { it.state.value is DownloadState.Downloading })
    assertTrue((running + queued).all { it.state.value is DownloadState.Paused })
    assertTrue(done.calls.isEmpty())
    assertEquals("Paused 5 downloads", controller.messages.active.value.last().title)
    controller.close()
  }

  @Test
  fun pauseAll_oneTaskFails_pausesTheOthersAndReportsIt() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)
    val failing = api.add(downloading).apply { failure = CancellationException("Client closed") }
    val others = List(2) { api.add(downloading) }

    controller.state.pauseAll()
    runCurrent()

    assertTrue(others.all { it.state.value is DownloadState.Paused })
    assertEquals(listOf("pause"), failing.calls)
    val toast = controller.messages.active.value.last()
    assertEquals(MessageLevel.Warning, toast.level)
    assertEquals("Paused 2 downloads · 1 failed", toast.title)
    controller.close()
  }

  @Test
  fun pauseAll_nothingPaused_leavesUndoToTheEarlierOperation() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)
    val removed = api.add(DownloadState.Completed("/downloads/a.iso"))
    api.add(downloading).apply { failure = IllegalStateException("Server said no") }

    controller.state.remove(listOf(removed))
    controller.state.pauseAll()
    runCurrent()

    assertEquals(listOf("Remove"), controller.state.pendingOps.ops.value.map { it.label })
    assertEquals(1, controller.errors().size)
    assertTrue(controller.state.pendingOps.undoLast())
    controller.close()
  }

  @Test
  fun pauseAll_undo_resumesExactlyThePausedTasks() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)
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
    controller.close()
  }

  @Test
  fun remove_hidesRowsAtOnceAndUndoRestoresThem() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)
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
    controller.close()
  }

  @Test
  fun remove_windowEnds_removesTheTaskAfterSixSeconds() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)
    val task = api.add(DownloadState.Completed("/downloads/a.iso"))

    controller.state.remove(listOf(task))
    advanceTimeBy(5.seconds)
    runCurrent()
    assertTrue(task.calls.isEmpty())

    advanceTimeBy(1001.milliseconds)
    runCurrent()
    assertEquals(listOf("remove deleteFiles=false"), task.calls)
    assertTrue(api.tasks.value.isEmpty())
    controller.close()
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
  fun sendTo_copy_keepsTheTaskOptionsAndTheSource() = runTest {
    val api = RecordingKetchApi()
    val nas = RecordingKetchApi("NAS")
    val controller = controller(api)
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
    assertEquals("Sent ubuntu.iso to NAS", controller.messages.history.value.first().title)
    controller.close()
  }

  @Test
  fun sendTo_moveAndAdded_removesTheSourceWhenTheWindowEnds() = runTest {
    val api = RecordingKetchApi()
    val nas = RecordingKetchApi("NAS")
    val controller = controller(api)
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
    assertEquals(listOf("remove deleteFiles=false"), task.calls)
    controller.close()
  }

  @Test
  fun sendTo_moveButAddFailed_keepsTheSource() = runTest {
    val api = RecordingKetchApi()
    val nas = RecordingKetchApi("NAS").apply {
      downloadFailure = { IllegalStateException("Connection refused") }
    }
    val controller = controller(api)
    val task = api.add(paused)

    controller.state.sendTo(listOf(task), EmbeddedInstance(nas, "NAS"), move = true)
    advanceTimeBy(10.seconds)
    runCurrent()

    assertTrue(task.calls.isEmpty())
    assertTrue(controller.state.pendingOps.hidden.value.isEmpty())
    assertEquals("Couldn't send file1.bin to NAS", controller.errors().single().title)
    controller.close()
  }

  @Test
  fun aiDownloadSelected_oneCandidateFails_addsTheOthers() = runTest {
    val api = RecordingKetchApi().apply {
      downloadFailure = { if ("broken" in it.url) IllegalStateException("Not found") else null }
    }
    val controller = controller(api)
    val candidates = listOf("a.iso", "broken.iso", "b.iso").map {
      AiCandidate("https://example.com/$it", title = it, confidence = 0.9f, description = "")
    }

    controller.state.aiDownloadSelected(candidates)
    runCurrent()

    assertEquals(
      listOf("https://example.com/a.iso", "https://example.com/b.iso"),
      api.requests.map { it.url },
    )
    assertEquals(
      "Added 2 downloads → This Mac · 1 failed",
      controller.messages.history.value.first().title,
    )
    controller.close()
  }

  @Test
  fun startNow_noFreeSlot_namesThePreemptedTaskAndUndoRestoresIt() = runTest {
    val api = RecordingKetchApi(maxActive = 1)
    val controller = controller(api)
    val running = api.add(downloading, DownloadRequest("https://example.com/debian.iso"))
    val waiting = api.add(DownloadState.Queued, DownloadRequest("https://example.com/blender.dmg"))

    controller.state.startNow(waiting)
    runCurrent()
    assertEquals(
      "Started blender.dmg now · paused debian.iso to make room",
      controller.messages.active.value.last().title,
    )
    assertIs<DownloadState.Downloading>(waiting.state.value)

    controller.click("Undo")
    runCurrent()
    assertEquals(DownloadPriority.NORMAL, waiting.request.priority)
    assertIs<DownloadState.Downloading>(running.state.value)
    assertIs<DownloadState.Queued>(waiting.state.value)
    controller.close()
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
    val toast = controller.messages.active.value.last()
    assertEquals("Added ubuntu.iso → This Mac", toast.title)
    assertEquals(listOf("Options", "Undo"), toast.actions.map { it.label })
    controller.close()
  }

  @Test
  fun quickAdd_undo_removesTheTaskAndItsFile() = runTest {
    val api = RecordingKetchApi()
    val controller = controller(api)

    controller.state.quickAdd(listOf("https://example.com/ubuntu.iso"))
    runCurrent()
    val task = api.tasks.value.single() as RecordingTask
    controller.click("Undo")
    runCurrent()

    assertEquals(listOf("remove deleteFiles=true"), task.calls)
    controller.close()
  }
}
