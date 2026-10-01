package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.app.feedback.AppMessage
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.instance.DeviceScope
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.instance.RemoteInstance
import com.linroid.ketch.config.RemoteConfig
import com.linroid.ketch.remote.ConnectionState
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

private const val NAS_ID = "nas.local:8642"

@OptIn(ExperimentalCoroutinesApi::class)
class AllDevicesTest {

  private val downloading = DownloadState.Downloading(DownloadProgress(10, 100, 50))

  private fun task(id: String) = ListTestTask(id, DownloadState.Queued)
  private val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=ubuntu"

  /** A device that records what it is asked to resolve. */
  private class ResolvingApi(
    val recording: RecordingKetchApi,
  ) : KetchApi by recording {
    val resolved = mutableListOf<String>()

    override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource {
      resolved += url
      return ResolvedSource(
        url = url,
        sourceType = if (url.startsWith("magnet:")) "torrent" else "http",
        totalBytes = 1000,
        supportsResume = true,
        suggestedFileName = "file.bin",
        maxSegments = 4,
      )
    }
  }

  /** This Mac and a connected NAS, with the app over them. */
  private class Fleet(scope: TestScope) {
    val mac = ResolvingApi(RecordingKetchApi("This Mac"))
    val nas = ResolvingApi(RecordingKetchApi("NAS"))
    val manager = InstanceManager(
      factory = InstanceFactory(
        deviceName = "This Mac",
        embeddedFactory = { mac },
        remoteFactory = { config ->
          RemoteInstance(nas, config, MutableStateFlow(ConnectionState.Connected))
        },
      ),
      initialRemotes = listOf(RemoteConfig(host = "nas.local", name = "NAS")),
      context = scope.backgroundScope.coroutineContext,
    )
    // A child of the background scope, so a failed test never leaves its loops running.
    val controller = AppController(
      instanceManager = manager,
      context = scope.backgroundScope.coroutineContext +
        SupervisorJob(scope.backgroundScope.coroutineContext[Job]),
      clock = ListFixtures.clock(scope),
    )
    val state: AppState get() = controller.state
    val local: InstanceEntry get() = manager.instances.value.first()
    val remote: InstanceEntry get() = manager.instances.value.last()

    fun messages(): List<AppMessage> = controller.messages.history.value
  }

  private fun TestScope.fleet(): Fleet = Fleet(this).also {
    runCurrent()
    advanceTimeBy(1.seconds)
  }

  @Test
  fun showAllDevices_twoDevices_listsTheTasksOfBoth() = runTest {
    val fleet = fleet()
    val here = fleet.mac.recording.add(downloading)
    val there = fleet.nas.recording.add(DownloadState.Paused(RecordingTask.PROGRESS))
    advanceTimeBy(1.seconds)
    assertEquals(listOf(here), fleet.state.tasks.value)

    assertTrue(fleet.state.showAllDevices())
    advanceTimeBy(1.seconds)

    assertEquals(listOf(here, there), fleet.state.tasks.value)
    assertEquals(listOf(fleet.local, fleet.remote), fleet.state.shownInstances.value)
    val pulse = fleet.state.pulse.state.value
    assertTrue(pulse.allDevices)
    assertEquals(PulseCounts(downloading = 1, paused = 1), pulse.counts)
    fleet.controller.close()
  }

  @Test
  fun rows_oneDeviceShown_keepTheOthersInAllRows() = runTest {
    val shown = MutableStateFlow<DeviceScope>(DeviceScope.Single(LOCAL_DEVICE_ID))
    val nas = DeviceInfo("NAS", RowCapabilities.remote())
    val model = TaskListModel(
      sources = flowOf(
        listOf(
          TaskListSource(LOCAL_DEVICE_ID, ListFixtures.device, flowOf(listOf(task("a")))),
          TaskListSource(NAS_ID, nas, flowOf(listOf(task("b")))),
        ),
      ),
      scope = backgroundScope,
      shown = shown,
      clock = ListFixtures.clock(this),
      timeZone = { TimeZone.UTC },
      dispatcher = StandardTestDispatcher(testScheduler),
    )
    advanceTimeBy(300)
    assertEquals(listOf(LOCAL_DEVICE_ID), model.rows.value.map { it.key.deviceId })
    assertEquals(listOf(LOCAL_DEVICE_ID, NAS_ID), model.allRows.value.map { it.key.deviceId })
    assertEquals(1, model.counts.value[StatusFilter.All])

    shown.value = DeviceScope.All
    advanceTimeBy(300)

    assertEquals(listOf(LOCAL_DEVICE_ID, NAS_ID), model.rows.value.map { it.key.deviceId })
    assertEquals(2, model.view.value.rows.size)
    assertEquals(TaskKey(NAS_ID, "b"), model.row(TaskKey(NAS_ID, "b"))?.key)
  }

  @Test
  fun showAllDevices_oneDevice_keepsShowingIt() = runTest {
    val fleet = fleet()
    fleet.manager.removeInstance(fleet.remote)
    runCurrent()

    assertTrue(!fleet.state.showAllDevices())
    assertEquals(DeviceScope.Single(LOCAL_DEVICE_ID), fleet.state.deviceScope.value)
    fleet.controller.close()
  }

  @Test
  fun taskCommand_nasTaskUnderAllDevices_callsTheNasApi() = runTest {
    val fleet = fleet()
    fleet.mac.recording.add(downloading)
    val task = fleet.nas.recording.add(downloading)
    fleet.state.showAllDevices()
    advanceTimeBy(1.seconds)
    val shown = fleet.state.tasks.value.single { fleet.state.keyOf(it).deviceId == NAS_ID }

    fleet.state.runTaskCommand(shown, "pause") { pause() }
    runCurrent()

    assertEquals(listOf("pause"), task.calls)
    assertEquals(fleet.remote, fleet.state.deviceOf(shown))
    fleet.controller.close()
  }

  @Test
  fun tasksOf_keysOfTwoDevices_findsEachOnItsDevice() = runTest {
    val fleet = fleet()
    val here = fleet.mac.recording.add(downloading)
    val there = fleet.nas.recording.add(downloading)

    val keys = listOf(
      TaskKey(NAS_ID, there.taskId),
      TaskKey(LOCAL_DEVICE_ID, here.taskId),
      TaskKey(NAS_ID, "gone"),
    )

    val tasks = fleet.state.tasksOf(keys)

    assertEquals(listOf<DownloadTask>(there, here), tasks)
    fleet.controller.close()
  }

  @Test
  fun pauseAll_allDevices_pausesEveryDeviceInOneMessage() = runTest {
    val fleet = fleet()
    val here = fleet.mac.recording.add(downloading)
    val there = fleet.nas.recording.add(downloading)
    fleet.state.showAllDevices()
    advanceTimeBy(1.seconds)

    fleet.state.pauseAll()
    runCurrent()

    assertEquals(listOf("pause"), here.calls)
    assertEquals(listOf("pause"), there.calls)
    assertEquals("Paused 2 downloads on 2 devices", fleet.messages().first().title)
    fleet.controller.close()
  }

  @Test
  fun pauseAll_oneDeviceShown_leavesTheOtherAlone() = runTest {
    val fleet = fleet()
    val here = fleet.mac.recording.add(downloading)
    val there = fleet.nas.recording.add(downloading)

    fleet.state.pauseAll()
    runCurrent()

    assertEquals(listOf("pause"), here.calls)
    assertTrue(there.calls.isEmpty())
    assertEquals("Paused 1 download", fleet.messages().first().title)
    fleet.controller.close()
  }

  @Test
  fun switchInstance_activeDeviceUnderAllDevices_showsItAlone() = runTest {
    val fleet = fleet()
    fleet.state.showAllDevices()
    runCurrent()

    fleet.state.switchInstance(fleet.local)
    runCurrent()

    assertEquals(DeviceScope.Single(LOCAL_DEVICE_ID), fleet.state.deviceScope.value)
    assertEquals(listOf(fleet.local), fleet.state.shownInstances.value)
    fleet.controller.close()
  }

  @Test
  fun sendTo_cookiesToARemoteDevice_asksFirstAndThenSendsTheHeaders() = runTest {
    val fleet = fleet()
    val headers = mapOf("Cookie" to "session=1", "Referer" to "https://example.com/")
    val task = fleet.mac.recording.add(
      DownloadState.Paused(RecordingTask.PROGRESS),
      DownloadRequest("https://example.com/ubuntu.iso", headers = headers),
    )

    fleet.state.sendTo(listOf(task), fleet.remote)
    runCurrent()
    val confirmation = assertNotNull(fleet.state.sendConfirmation)
    assertEquals("Cookies from your browser will be sent to NAS.", confirmation.warning)
    assertTrue(fleet.nas.recording.requests.isEmpty())

    fleet.state.confirmSend()
    runCurrent()

    assertNull(fleet.state.sendConfirmation)
    assertEquals(headers, fleet.nas.recording.requests.single().headers)
    assertEquals("Sent ubuntu.iso to NAS", fleet.messages().first().title)
    fleet.controller.close()
  }

  @Test
  fun sendTo_noCredentials_sendsAtOnce() = runTest {
    val fleet = fleet()
    val task = fleet.mac.recording.add(
      DownloadState.Paused(RecordingTask.PROGRESS),
      DownloadRequest("https://example.com/a.iso", headers = mapOf("Referer" to "https://x/")),
    )

    fleet.state.sendTo(listOf(task), fleet.remote)
    runCurrent()

    assertNull(fleet.state.sendConfirmation)
    assertEquals(1, fleet.nas.recording.requests.size)
    fleet.controller.close()
  }

  @Test
  fun credentialWarning_signInOnly_namesTheSignIn() {
    assertEquals(
      "Sign-in details will be sent to NAS.",
      credentialWarning(listOf(mapOf("authorization" to "Basic x")), "NAS"),
    )
    assertNull(credentialWarning(listOf(mapOf("User-Agent" to "Ketch")), "NAS"))
  }

  @Test
  fun intake_targetedAtTheNas_resolvesThroughTheNas() = runTest {
    val fleet = fleet()
    val request = IntakeRequest(
      text = "https://example.com/ubuntu.iso",
      targetDeviceId = fleet.remote.deviceId,
    )

    val session = fleet.state.intake.start(request)
    runCurrent()

    assertEquals(fleet.remote, session.target)
    assertEquals(listOf("https://example.com/ubuntu.iso"), fleet.nas.resolved)
    assertTrue(fleet.mac.resolved.isEmpty())
    fleet.state.intake.release(session)
    fleet.controller.close()
  }

  @Test
  fun intake_magnetSentToTheNas_sendsTheNextMagnetThereToo() = runTest {
    val fleet = fleet()
    val first = fleet.state.intake.start(IntakeRequest(text = magnet))
    runCurrent()
    assertEquals(fleet.local, first.target)

    first.selectTarget(fleet.remote)
    fleet.state.intake.release(first)
    val magnets = fleet.state.intake.start(IntakeRequest(text = "$magnet&tr=x"))
    val links = fleet.state.intake.start(IntakeRequest(text = "https://example.com/a.iso"))
    runCurrent()

    assertEquals(fleet.remote, magnets.target)
    assertEquals(fleet.local, links.target)
    fleet.state.intake.release(magnets)
    fleet.state.intake.release(links)
    fleet.controller.close()
  }

  @Test
  fun intakeKind_onlyMagnetsAndTorrents_isTorrents() {
    assertEquals(IntakeKind.Torrents, IntakeKind.of(listOf(magnet), files = 1))
    assertEquals(IntakeKind.Torrents, IntakeKind.of(emptyList(), files = 2))
    assertEquals(IntakeKind.Links, IntakeKind.of(listOf(magnet, "https://example.com/a.iso")))
    assertEquals(IntakeKind.Links, IntakeKind.of(emptyList()))
  }

  @Test
  fun quickAddTarget_allDevices_isTheLastLinkTarget() = runTest {
    val fleet = fleet()
    fleet.state.rememberTarget(IntakeKind.Links, fleet.remote)
    assertEquals(fleet.local, fleet.state.quickAddTarget())

    fleet.state.showAllDevices()
    runCurrent()

    assertEquals(fleet.remote, fleet.state.quickAddTarget())
    fleet.controller.close()
  }

  @Test
  fun redownload_severalTasks_postsOneMessage() = runTest {
    val fleet = fleet()
    val tasks = List(3) { fleet.mac.recording.add(DownloadState.Canceled) }

    fleet.state.redownload(tasks)
    runCurrent()

    assertTrue(tasks.all { it.calls == listOf("remove deleteFiles=true") })
    assertEquals(3, fleet.mac.recording.requests.size)
    assertEquals(listOf("Restarted 3 downloads"), fleet.messages().map { it.title })
    fleet.controller.close()
  }

  @Test
  fun redownload_addFails_tryAgainAddsTheRequestAgain() = runTest {
    val fleet = fleet()
    var failing = true
    fleet.mac.recording.downloadFailure = {
      if (failing) IllegalStateException("Connection lost") else null
    }
    val task = fleet.mac.recording.add(
      DownloadState.Failed(KetchError.FileChanged("ETag changed")),
      DownloadRequest("https://example.com/ubuntu.iso"),
    )

    fleet.state.redownload(task)
    runCurrent()
    val error = fleet.messages().first()
    assertEquals(MessageLevel.Error, error.level)
    assertEquals("Couldn't download ubuntu.iso again on This Mac", error.title)

    failing = false
    error.actions.single { it.label == "Try again" }.onClick()
    runCurrent()

    assertEquals(listOf("remove deleteFiles=true"), task.calls)
    assertEquals("https://example.com/ubuntu.iso", fleet.mac.recording.requests.single().url)
    fleet.controller.close()
  }

  @Test
  fun startNow_severalTasks_postsOneMessageWithOneUndo() = runTest {
    val fleet = fleet()
    val tasks = List(2) { fleet.mac.recording.add(DownloadState.Queued) }

    fleet.state.startNow(tasks)
    runCurrent()

    val message = fleet.messages().single()
    assertEquals("Started 2 downloads now", message.title)
    assertTrue(tasks.all { it.state.value is DownloadState.Downloading })
    message.actions.single { it.label == "Undo" }.onClick()
    runCurrent()
    assertTrue(tasks.all { it.request.priority == DownloadPriority.NORMAL })
    fleet.controller.close()
  }

  @Test
  fun retryFailed_allDevices_retriesEveryDevice() = runTest {
    val fleet = fleet()
    val here = fleet.mac.recording.add(DownloadState.Failed(KetchError.Network()))
    val there = fleet.nas.recording.add(DownloadState.Failed(KetchError.Network()))
    fleet.state.showAllDevices()
    advanceTimeBy(1.seconds)

    fleet.state.retryFailed()
    runCurrent()

    assertEquals(listOf("resume"), here.calls)
    assertEquals(listOf("resume"), there.calls)
    assertEquals("Retrying 2 downloads on 2 devices", fleet.messages().first().title)
    fleet.controller.close()
  }
}
