package com.linroid.ketch.app.state

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.SystemInfo
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.util.extractFilename
import com.linroid.ketch.config.IntakePreferences
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.UiPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
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
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class IntakeStateTest {

  private val links = (1..5).map { "https://cdn.example.com/files/archive-$it.zip" }

  @Test
  fun submit_fiveLinks_addsFiveTasks() = runTest {
    val api = IntakeTestApi()
    val session = session(api, IntakeRequest(links.joinToString("\n")))

    assertEquals(5, session.summary.links)
    assertEquals(5, session.summary.ready)
    assertTrue(session.summary.text.startsWith("5 links · 5 ready · "))
    assertEquals("Add 5 downloads", session.primaryLabel)
    var closed = false
    session.submit { closed = true }
    runCurrent()

    assertTrue(closed)
    assertEquals(links, api.base.requests.map { it.url })
  }

  @Test
  fun submit_oneLinkFails_addsTheOthers() = runTest {
    val api = IntakeTestApi()
    api.base.downloadFailure = { if (it.url == links[2]) KetchError.Network() else null }
    val (state, session) = stateAndSession(api, IntakeRequest(links.joinToString("\n")))

    session.submit {}
    runCurrent()

    assertEquals(4, api.base.requests.size)
    val toast = state.messages.active.value.last()
    assertEquals(MessageLevel.Warning, toast.level)
    assertTrue(toast.title.endsWith("· 1 failed"), toast.title)
    assertEquals("Review", toast.actions.first().label)
  }

  @Test
  fun submit_oneCheckFails_keepsItOutAndAddsTheRest() = runTest {
    val api = IntakeTestApi(check = { url, _ ->
      if (url == links[0]) throw KetchError.Http(404) else ready(url)
    })
    val session = session(api, IntakeRequest(links.joinToString("\n")))

    assertEquals(1, session.summary.attention)
    assertEquals("Add 4 downloads", session.primaryLabel)
    session.submit {}
    runCurrent()

    assertEquals(links.drop(1), api.base.requests.map { it.url })
  }

  @Test
  fun request_manyLinks_checksAtMostFourAtOnce() = runTest {
    val gate = CompletableDeferred<Unit>()
    var running = 0
    var peak = 0
    val api = IntakeTestApi(check = { url, _ ->
      running++
      peak = maxOf(peak, running)
      gate.await()
      running--
      ready(url)
    })
    val many = (1..9).joinToString("\n") { "https://cdn.example.com/part$it.bin" }
    val session = session(api, IntakeRequest(many))

    assertEquals(4, peak)
    assertEquals(9, session.summary.checking)
    gate.complete(Unit)
    runCurrent()
    assertEquals(9, session.summary.ready)
  }

  @Test
  fun primaryLabel_magnetWithoutFileList_waitsUntilItArrives() = runTest {
    val metadata = CompletableDeferred<ResolvedSource>()
    val api = IntakeTestApi(check = { _, _ -> metadata.await() })
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Show"
    val session = session(api, IntakeRequest(magnet))

    assertEquals("Waiting for file list", session.primaryLabel)
    assertFalse(session.canSubmit)
    metadata.complete(torrent(magnet))
    runCurrent()

    assertTrue(session.canSubmit)
    val entry = session.entries.single()
    assertEquals(entry, session.torrentStage)
    assertEquals(setOf("0", "1"), entry.selectedFiles)
    assertEquals("Add 2 files", session.primaryLabel)
  }

  @Test
  fun start_bareInfoHashAndMissingScheme_becomeLinks() = runTest {
    val hash = "0123456789abcdef0123456789abcdef01234567"
    val session = session(IntakeTestApi(), IntakeRequest("$hash\nexample.com/files/a.iso"))

    assertEquals(
      listOf("magnet:?xt=urn:btih:$hash", "https://example.com/files/a.iso"),
      session.entries.map { it.url },
    )
  }

  @Test
  fun submit_headersAndSeed_reachTheCheckAndTheRequest() = runTest {
    val checked = mutableListOf<Map<String, String>>()
    val api = IntakeTestApi(check = { url, properties ->
      checked += properties
      ready(url)
    })
    val seed = IntakeSeed(
      url = "https://files.example.com/a.pdf",
      fileName = "report.pdf",
      headers = mapOf("Cookie" to "id=1"),
      properties = mapOf("ketch.origin" to "browser"),
    )
    val session = session(api, IntakeRequest(seeds = listOf(seed)))
    session.headers.referer = "https://files.example.com/"
    session.onHeadersChanged()
    advanceTimeBy(1_000)
    runCurrent()

    session.submit {}
    runCurrent()

    val headers = mapOf("Cookie" to "id=1", "Referer" to "https://files.example.com/")
    assertEquals(headers, checked.last())
    val request = api.base.requests.single()
    assertEquals(headers, request.headers)
    assertEquals(mapOf("ketch.origin" to "browser"), request.properties)
    assertEquals(Destination("report.pdf"), request.destination)
  }

  @Test
  fun submit_plainLink_marksTheAppAsOrigin() = runTest {
    val checked = mutableListOf<Map<String, String>>()
    val api = IntakeTestApi(check = { url, properties ->
      checked += properties
      ready(url)
    })
    val session = session(api, IntakeRequest(links.first()))

    session.submit {}
    runCurrent()

    assertEquals(mapOf("ketch.origin" to "app"), api.base.requests.single().properties)
    assertTrue(checked.single().isEmpty())
  }

  @Test
  fun entries_linkAlreadyInKetch_isLeftOutUntilAddedAgain() = runTest {
    val api = IntakeTestApi()
    api.base.add(
      DownloadState.Completed("/downloads/archive-1.zip", totalBytes = 10),
      DownloadRequest(links.first()),
    )
    val session = session(api, IntakeRequest(links.take(2).joinToString("\n")))

    val duplicate = session.entries.first()
    assertNotNull(duplicate.duplicate)
    assertFalse(duplicate.addable)
    assertEquals(1, session.summary.duplicates)
    duplicate.downloadAgain = true

    assertTrue(duplicate.addable)
  }

  @Test
  fun submit_options_saveTheStickyDefaultsOfTheDevice() = runTest {
    val api = IntakeTestApi()
    val (state, session) = stateAndSession(api, IntakeRequest(links.first()))
    session.folder = "/Volumes/Data"
    session.priority = DownloadPriority.HIGH
    session.connections = 4
    session.speedLimit = SpeedLimit.mbps(2)

    session.submit {}
    runCurrent()

    val request = api.base.requests.single()
    assertEquals(Destination("/Volumes/Data/"), request.destination)
    assertEquals(DownloadPriority.HIGH, request.priority)
    assertEquals(4, request.connections)
    assertEquals(SpeedLimit.mbps(2), request.speedLimit)
    val saved = state.appSettings.ui.intake.getValue(LOCAL_DEVICE_ID)
    assertEquals(IntakePreferences("/Volumes/Data", DownloadPriority.HIGH, 4), saved)
  }

  @Test
  fun start_stickyDefaults_prefillTheOptions() = runTest {
    val prefs = IntakePreferences("/Volumes/Data", DownloadPriority.LOW, 6)
    val config = KetchConfig(ui = UiPreferences(intake = mapOf(LOCAL_DEVICE_ID to prefs)))
    val (_, session) = stateAndSession(IntakeTestApi(), IntakeRequest(), config)

    assertEquals("/Volumes/Data", session.folder)
    assertEquals(DownloadPriority.LOW, session.priority)
    assertEquals(6, session.connections)
  }

  @Test
  fun recentFolders_finishedAndDirectoryTasks_newestFirst() = runTest {
    val api = IntakeTestApi()
    api.base.add(
      DownloadState.Completed("/Users/me/Movies/a.mkv"),
      DownloadRequest("https://a.example/a.mkv"),
    )
    api.base.add(
      DownloadState.Queued,
      DownloadRequest("https://a.example/b.iso", destination = Destination("/Volumes/ISO/")),
    )
    val session = session(api, IntakeRequest())

    assertEquals(listOf("/Volumes/ISO", "/Users/me/Movies"), session.recentFolders)
  }

  @Test
  fun retry_onlyOptionsChanged_keepsTheTaskAndResumes() = runTest {
    val api = IntakeTestApi()
    val failed = api.base.add(
      DownloadState.Failed(KetchError.Network()),
      DownloadRequest(links.first()),
    )
    val request = IntakeRequest(
      seeds = listOf(IntakeSeed(links.first())),
      retryOf = TaskKey(LOCAL_DEVICE_ID, failed.taskId),
    )
    val session = session(api, request)
    session.priority = DownloadPriority.HIGH

    assertFalse(session.startsOver)
    assertEquals("Retry", session.primaryLabel)
    session.submit {}
    runCurrent()

    assertEquals(listOf("priority HIGH", "resume"), failed.calls)
    assertTrue(api.base.requests.isEmpty())
  }

  @Test
  fun retry_linkChanged_startsOverAsANewTask() = runTest {
    val api = IntakeTestApi()
    val failed = api.base.add(
      DownloadState.Failed(KetchError.Http(403)),
      DownloadRequest(links.first()),
    )
    val request = IntakeRequest(
      seeds = listOf(IntakeSeed(links.first())),
      retryOf = TaskKey(LOCAL_DEVICE_ID, failed.taskId),
    )
    val session = session(api, request)
    session.headers.cookie = "session=1"

    assertTrue(session.startsOver)
    assertEquals("Start over", session.primaryLabel)
    session.submit {}
    runCurrent()

    assertEquals(listOf("remove deleteFiles=true"), failed.calls)
    assertEquals(mapOf("Cookie" to "session=1"), api.base.requests.single().headers)
  }

  @Test
  fun remove_linkInTheText_takesItsLineOut() = runTest {
    val session = session(IntakeTestApi(), IntakeRequest(links.take(3).joinToString("\n")))

    session.remove(session.entries[1])

    assertEquals(listOf(links[0], links[2]).joinToString("\n"), session.text.text)
    assertEquals(listOf(links[0], links[2]), session.entries.map { it.url })
  }

  @Test
  fun intakeDestination_folderAndName_joinWithTheDeviceSeparator() {
    assertNull(intakeDestination(null, null, "/", torrent = false))
    assertEquals(Destination("a.iso"), intakeDestination(null, "a.iso", "/", torrent = false))
    assertEquals(Destination("D:\\Iso\\"), intakeDestination("D:\\Iso", null, "\\", false))
    assertEquals(
      Destination("/data/a.iso"),
      intakeDestination("/data/", "a.iso", "/", torrent = false),
    )
  }

  @Test
  fun intakeDestination_systemFolder_takesNoNameAndNoTorrents() {
    val tree = "content://com.android.externalstorage.documents/tree/primary%3ADownload"
    assertEquals(Destination(tree), intakeDestination(tree, "a.iso", "/", torrent = false))
    assertNull(intakeDestination(tree, null, "/", torrent = true))
  }

  @Test
  fun intakeOutcome_freeSlots_startNowWithTheTime() {
    val config = DownloadConfig(maxConcurrentDownloads = 2)
    val running = listOf(task("a", downloading(speed = 1_000_000)))
    val outcome = intakeOutcome(
      hosts = listOf("a.example"),
      priority = DownloadPriority.NORMAL,
      schedule = DownloadSchedule.Immediate,
      config = config,
      tasks = running,
      bytes = 240_000_000,
      now = NOW,
      zone = TimeZone.UTC,
    )

    assertEquals("Starts now · 1 of 2 slots free · ≈ 4 min at current speed", outcome)
  }

  @Test
  fun intakeOutcome_slotsFull_isQueuedBehindTheWaitingOnes() {
    val config = DownloadConfig(maxConcurrentDownloads = 1)
    val tasks = listOf(
      task("a", downloading()),
      task("b", DownloadState.Queued),
      task("c", DownloadState.Queued, DownloadPriority.LOW),
    )

    val outcome = outcome(listOf("x.example"), DownloadPriority.NORMAL, config, tasks)

    assertEquals("Queued · 2nd in line", outcome)
  }

  @Test
  fun intakeOutcome_urgentWithFullSlots_namesTheTaskItPauses() {
    val config = DownloadConfig(maxConcurrentDownloads = 1)
    val tasks = listOf(task("debian-12.iso", downloading(), DownloadPriority.LOW))

    val outcome = outcome(listOf("x.example"), DownloadPriority.URGENT, config, tasks)

    assertEquals("⚡ Urgent: starts now and pauses debian-12.iso (Low)", outcome)
  }

  @Test
  fun intakeOutcome_hostFull_waitsForTheServer() {
    val config = DownloadConfig(maxConcurrentDownloads = 4, maxConnectionsPerHost = 1)
    val tasks = listOf(task("a", downloading(), host = "github.com"))

    val outcome = outcome(listOf("github.com"), DownloadPriority.NORMAL, config, tasks)

    assertEquals("Waits for github.com · 1 per server", outcome)
  }

  @Test
  fun intakeOutcome_batch_splitsStartingAndQueued() {
    val config = DownloadConfig(maxConcurrentDownloads = 2)
    val hosts = List(6) { "h$it.example" }

    val outcome = outcome(hosts, DownloadPriority.NORMAL, config, emptyList())

    assertEquals("2 start now (2 of 2 slots free) · 4 queued", outcome)
  }

  @Test
  fun replaceIntakeLink_linkInProse_replacesOnlyThatWord() {
    val text = "first https://a.example/x.iso then https://b.example/y.iso"

    val edited = replaceIntakeLink(text, "https://a.example/x.iso", replacement = null)

    assertEquals("first then https://b.example/y.iso", edited)
  }

  @Test
  fun replaceIntakeLink_curlCommand_replacesTheWholeCommand() {
    val text = "curl 'https://a.example/x.iso' \\\n  -H 'Cookie: a=1'\nhttps://b.example/y.iso"

    val edited = replaceIntakeLink(text, "https://a.example/x.iso", "https://c.example/z.iso")

    assertEquals("https://c.example/z.iso\nhttps://b.example/y.iso", edited)
  }

  @Test
  fun defaultTorrentSelection_extras_areLeftOut() {
    val files = listOf(
      SourceFile("0", "Show/S01E01.mkv", 1_000_000),
      SourceFile("1", "Show/Sample/sample.mkv", 100_000),
      SourceFile("2", "Show/info.nfo", 2_000),
      SourceFile("3", "Show/readme.txt", 300),
      SourceFile("4", "Show/notes.txt", 4_096),
    )

    assertEquals(setOf("0", "4"), defaultTorrentSelection(files))
  }

  @Test
  fun ordinal_numbers_useTheirSuffix() {
    assertEquals(
      listOf("1st", "2nd", "3rd", "4th", "11th", "12th", "13th", "21st", "102nd"),
      listOf(1, 2, 3, 4, 11, 12, 13, 21, 102).map(::ordinal),
    )
  }

  private fun outcome(
    hosts: List<String?>,
    priority: DownloadPriority,
    config: DownloadConfig,
    tasks: List<ListTestTask>,
  ): String? = intakeOutcome(
    hosts = hosts,
    priority = priority,
    schedule = DownloadSchedule.Immediate,
    config = config,
    tasks = tasks,
    bytes = null,
    now = NOW,
    zone = TimeZone.UTC,
  )

  private fun task(
    name: String,
    state: DownloadState,
    priority: DownloadPriority = DownloadPriority.NORMAL,
    host: String = "x.example",
  ) = ListTestTask(name, state, DownloadRequest("https://$host/$name", priority = priority))

  private fun downloading(speed: Long = 0): DownloadState =
    DownloadState.Downloading(DownloadProgress(10, 100, speed))

  private fun TestScope.session(api: KetchApi, request: IntakeRequest): IntakeSession =
    stateAndSession(api, request).second

  private fun TestScope.stateAndSession(
    api: KetchApi,
    request: IntakeRequest,
    config: KetchConfig = KetchConfig(),
  ): Pair<AppState, IntakeSession> {
    val store = RecordingConfigStore(config)
    val manager = InstanceManager(
      factory = InstanceFactory(deviceName = "This Mac", embeddedFactory = { api }),
      configStore = store,
    )
    val state = AppState(
      instanceManager = manager,
      scope = backgroundScope,
      appSettings = AppSettingsController(store),
    )
    runCurrent()
    val session = IntakeController(state, backgroundScope).start(request)
    runCurrent()
    return state to session
  }

  private companion object {
    val NOW: Instant = Instant.parse("2026-10-01T12:00:00Z")
  }
}

/** A device whose checks [check] answers, with [RecordingKetchApi]'s tasks. */
private class IntakeTestApi(
  val base: RecordingKetchApi = RecordingKetchApi(),
  private val check: suspend (String, Map<String, String>) -> ResolvedSource = { url, _ ->
    ready(url)
  },
) : KetchApi by base {
  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
    check(url, properties)

  override suspend fun status(): KetchStatus = KetchStatus(
    name = "This Mac",
    version = "1",
    revision = "r",
    uptime = 0,
    config = DownloadConfig(maxConcurrentDownloads = 3),
    system = SystemInfo(
      os = "Mac OS X",
      arch = "aarch64",
      separator = "/",
      javaVersion = "21",
      availableProcessors = 8,
      maxMemory = 0,
      totalMemory = 0,
      freeMemory = 0,
      downloadDirectory = "/Users/me/Downloads",
      totalSpace = 1L shl 40,
      freeSpace = 1L shl 39,
      usableSpace = 1L shl 39,
    ),
  )
}

private fun ready(url: String) = ResolvedSource(
  url = url,
  sourceType = "http",
  totalBytes = 1_000_000,
  supportsResume = true,
  suggestedFileName = extractFilename(url),
  maxSegments = 8,
)

private fun torrent(url: String) = ResolvedSource(
  url = url,
  sourceType = "torrent",
  totalBytes = 3_000,
  supportsResume = true,
  suggestedFileName = "Show",
  maxSegments = 3,
  metadata = mapOf("infoHash" to "0123456789abcdef0123456789abcdef01234567"),
  files = listOf(
    SourceFile("0", "S01E01.mkv", 1_000),
    SourceFile("1", "S01E02.mkv", 1_000),
    SourceFile("2", "sample.mkv", 1_000),
  ),
)
