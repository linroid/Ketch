package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.FakeKetchApi
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.feedback.ActivityEvent
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.platform.DroppedFile
import com.linroid.ketch.app.ui.feedback.toastDetail
import com.linroid.ketch.config.KetchConfig
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppStateSeamsTest {

  private val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=ubuntu"

  private fun TestScope.appState(
    api: KetchApi = FakeKetchApi(),
    incoming: IncomingDownloads = IncomingDownloads(),
    config: KetchConfig = KetchConfig(),
  ): AppState {
    val store = RecordingConfigStore(config)
    val manager = InstanceManager(
      factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { api }),
      configStore = store,
    )
    return AppState(
      instanceManager = manager,
      scope = backgroundScope,
      appSettings = AppSettingsController(store),
      incoming = incoming,
    )
  }

  private fun firstLink(state: AppState): String =
    assertNotNull(state.intakeRequest).text.lines().first()

  @Test
  fun incomingLinks_offered_openTheAddDialogWithTheFirstLink() = runTest {
    val incoming = IncomingDownloads()
    val state = appState(incoming = incoming)

    incoming.offerLinks(listOf(magnet, "https://example.com/b.iso"), LinkSource.OpenUrl)
    runCurrent()

    assertTrue(state.showAddDialog)
    assertEquals(magnet, firstLink(state))
  }

  @Test
  fun closeAddDialog_afterIncomingLinks_completesThemAndShowsTheNext() = runTest {
    val incoming = IncomingDownloads()
    val state = appState(incoming = incoming)
    incoming.offerLinks(listOf(magnet), LinkSource.OpenUrl)
    incoming.offerLinks(listOf("https://example.com/b.iso"), LinkSource.Share)
    runCurrent()

    state.closeAddDialog()
    runCurrent()

    assertEquals("https://example.com/b.iso", firstLink(state))
    state.closeAddDialog()
    runCurrent()
    assertFalse(state.showAddDialog)
    assertTrue(incoming.pendingLinks.value.isEmpty())
  }

  @Test
  fun incomingLinks_whileAnOpenedFileShows_waitForIt() = runTest {
    val incoming = IncomingDownloads()
    val state = appState(incoming = incoming)
    incoming.offer(IncomingDownload.Ready("a.torrent", byteArrayOf(1)))
    incoming.offerLinks(listOf(magnet), LinkSource.OpenUrl)
    runCurrent()

    assertEquals("a.torrent", state.openedDownload?.label)
    state.closeAddDialog()
    runCurrent()

    assertTrue(state.showAddDialog)
    assertEquals(magnet, firstLink(state))
  }

  @Test
  fun addDroppedText_link_opensTheAddDialogPrefilled() = runTest {
    val state = appState()

    state.addDroppedText("  https://example.com/ubuntu.iso\n")
    runCurrent()

    assertTrue(state.showAddDialog)
    assertEquals("https://example.com/ubuntu.iso", firstLink(state))
  }

  @Test
  fun addDroppedText_whileTheDialogShowsAFile_dropsItsResolution() = runTest {
    val state = appState()
    state.openIntake()
    state.resolveDroppedFile(DroppedFile("a.torrent") { _ -> byteArrayOf(1) })
    runCurrent()
    assertIs<ResolveState.Error>(state.resolveState)

    state.addDroppedText("https://example.com/b.iso")

    assertEquals(ResolveState.Idle, state.resolveState)
    assertEquals("https://example.com/b.iso", firstLink(state))
  }

  @Test
  fun incomingFile_whileTheDialogShowsALink_resolvesTheFile() = runTest {
    val api = FakeKetchApi()
    val incoming = IncomingDownloads()
    val state = appState(api = api, incoming = incoming)
    state.addDroppedText("https://example.com/a.iso")
    runCurrent()

    incoming.offer(IncomingDownload.Ready("b.torrent", byteArrayOf(1)))
    runCurrent()

    assertEquals("b.torrent", state.droppedFile?.name)
    assertEquals("b.torrent", api.lastResolvedFileName)
  }

  @Test
  fun pulse_removedTask_leavesTheCounts() = runTest {
    val api = RecordingKetchApi()
    val task = api.add(DownloadState.Downloading(RecordingTask.PROGRESS))
    val state = appState(api = api)
    runCurrent()
    assertEquals(1, state.pulse.state.value.counts.downloading)

    state.remove(listOf(task))
    advanceTimeBy(PulseModel.UPDATE_INTERVAL)
    runCurrent()

    assertEquals(0, state.pulse.state.value.counts.downloading)
  }

  @Test
  fun pulse_embeddedSpeedLimit_isTheCap() = runTest {
    val config = KetchConfig(download = DownloadConfig(speedLimit = SpeedLimit.mbps(5)))
    val state = appState(config = config)

    runCurrent()

    assertEquals(SpeedLimit.mbps(5), state.pulse.state.value.cap)
  }

  @Test
  fun showDownloads_fromAnotherTab_resetsTheTabAndAsksTheShell() = runTest {
    val state = appState()
    state.statusFilter = StatusFilter.Failed
    var requests = 0
    backgroundScope.launch { state.downloadsRequests.collect { requests++ } }
    runCurrent()

    state.showDownloads()
    runCurrent()

    assertEquals(StatusFilter.All, state.statusFilter)
    assertEquals(1, requests)
  }

  @Test
  fun openSettings_samePageAgain_countsAnotherRequest() = runTest {
    val state = appState()
    val speed = SettingsTarget(SettingsTarget.Page.Speed)

    state.openSettings(speed)
    state.openSettings(speed)

    assertEquals(speed, state.settingsRequest)
    assertEquals(2, state.settingsRequests)
  }

  @Test
  fun showShortcuts_untilShown_staysRequested() = runTest {
    val state = appState()

    state.showShortcuts()
    val requested = state.shortcutsRequested
    state.shortcutsShown()

    assertTrue(requested)
    assertFalse(state.shortcutsRequested)
  }

  @Test
  fun report_failedDownload_toastNamesTheFileAndTheProblem() = runTest {
    val state = appState()
    val request = DownloadRequest(url = "https://example.com/files/q3-report.pdf")

    state.report(
      ActivityEvent.Failed(
        taskKey = TaskKey(LOCAL_DEVICE_ID, "t1"),
        request = request,
        state = DownloadState.Failed(KetchError.Http(403)),
      )
    )

    val message = state.messages.active.value.single()
    assertEquals("Download failed", message.title)
    assertEquals("q3-report.pdf: Access denied (403)", toastDetail(message))
  }

  @Test
  fun report_addFromElsewhere_recordsItInTheHistory() = runTest {
    val state = appState()
    val key = TaskKey(LOCAL_DEVICE_ID, "t1")

    state.report(ActivityEvent.Added(key, DownloadRequest(url = "https://example.com/a.iso")))

    assertEquals(listOf("Added a.iso"), state.messages.history.value.map { it.title })
  }

  @Test
  fun report_addThisWindowAnnounced_isLeftOut() = runTest {
    val state = appState()
    val key = TaskKey(LOCAL_DEVICE_ID, "t1")

    state.announceAdded(listOf(key))
    state.report(ActivityEvent.Added(key, DownloadRequest(url = "https://example.com/a.iso")))

    assertEquals(emptyList(), state.messages.history.value)
  }

  @Test
  fun announceAdded_afterTheMonitorReportedIt_withdrawsThatEntry() = runTest {
    val state = appState()
    val key = TaskKey(LOCAL_DEVICE_ID, "t1")
    state.report(ActivityEvent.Added(key, DownloadRequest(url = "https://example.com/a.iso")))

    state.announceAdded(listOf(key))

    assertEquals(emptyList(), state.messages.history.value)
    assertEquals(0, state.messages.unreadCount.value)
  }

  @Test
  fun showDetails_oneRowSelected_inspectsItOnTheDownloadsPage() = runTest {
    val state = appState()
    val key = TaskKey(LOCAL_DEVICE_ID, "a")
    state.statusFilter = StatusFilter.Failed
    state.selectedKeys = setOf(key)
    var requests = 0
    backgroundScope.launch { state.downloadsRequests.collect { requests++ } }
    runCurrent()

    assertTrue(state.showDetails())
    runCurrent()

    assertEquals(key, state.inspectedTask)
    assertEquals(1, requests)
    assertEquals(StatusFilter.Failed, state.statusFilter)
  }

  @Test
  fun showDetails_nothingSelected_showsNothing() = runTest {
    val state = appState()

    assertFalse(state.showDetails())

    assertEquals(null, state.inspectedTask)
  }

  @Test
  fun closeInspector_severalSelected_clearsTheSelection() = runTest {
    val state = appState()
    val keys = setOf(TaskKey(LOCAL_DEVICE_ID, "a"), TaskKey(LOCAL_DEVICE_ID, "b"))
    state.inspect(keys.first())
    state.selectedKeys = keys

    state.closeInspector()

    assertEquals(emptySet(), state.selectedKeys)
    assertEquals(null, state.inspectedTask)
  }

  @Test
  fun closeInspector_oneSelected_keepsTheRowSelected() = runTest {
    val state = appState()
    val key = TaskKey(LOCAL_DEVICE_ID, "a")
    state.selectedKeys = setOf(key)
    state.inspect(key)

    state.closeInspector()

    assertEquals(setOf(key), state.selectedKeys)
    assertEquals(null, state.inspectedTask)
  }
}
