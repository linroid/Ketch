package com.linroid.ketch.app.state

import androidx.compose.ui.text.input.TextFieldValue
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadCategory
import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.RecordingConfigStore
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.warmStrings
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.testStatus
import com.linroid.ketch.app.testSystem
import com.linroid.ketch.app.util.extractFilename
import com.linroid.ketch.config.IntakePreferences
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.UiPreferences
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.TimeZone
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Instant

@OptIn(ExperimentalCoroutinesApi::class)
class IntakeStateTest {

  private val links = (1..5).map { "https://cdn.example.com/files/archive-$it.zip" }

  // Loading a string for the first time lets virtual time run, which would time magnets out.
  @BeforeTest
  fun loadStrings() = runTest { warmStrings() }

  @Test
  fun submit_fiveLinks_addsFiveTasks() = runTest {
    val api = IntakeTestApi()
    val session = session(api, IntakeRequest(links.joinToString("\n")))

    assertEquals(5, session.summary.links)
    assertEquals(5, session.summary.ready)
    assertTrue(session.summary.text.load().startsWith("5 links · 5 ready · "))
    assertEquals("Download 5 files · 4.8 MB", session.primaryLabel.load())
    var closed = false
    session.submit { closed = true }
    runCurrent()

    assertTrue(closed)
    assertEquals(links, api.base.requests.map { it.url })
  }

  @Test
  fun submit_links_announcesTheNewTasks() = runTest {
    val api = IntakeTestApi()
    val (state, session) = stateAndSession(api, IntakeRequest(links.take(2).joinToString("\n")))
    val heard = mutableListOf<List<TaskKey>>()
    backgroundScope.launch { state.addedTasks.collect { heard += it } }
    runCurrent()

    session.submit {}
    runCurrent()

    val keys = api.base.tasks.value.map { TaskKey(LOCAL_DEVICE_ID, it.taskId) }
    assertEquals(listOf(keys.toSet()), heard.map { it.toSet() })
  }

  @Test
  fun primaryLabel_oneLink_namesTheFile() = runTest {
    val session = session(IntakeTestApi(), IntakeRequest(links.first()))

    assertEquals("Download archive-1.zip", session.primaryLabel.load())
    assertEquals("download", session.submitVerb)
    assertEquals("↩ to download", session.submitHint("↩").load())
  }

  @Test
  fun primaryLabel_scheduled_saysScheduleWithoutTheSize() = runTest {
    val session = session(IntakeTestApi(), IntakeRequest(links.take(2).joinToString("\n")))

    session.schedule = DownloadSchedule.AtTime(Instant.parse("2030-01-01T01:00:00Z"))

    assertEquals("Schedule 2 files", session.primaryLabel.load())
    assertEquals("schedule", session.submitVerb)
    assertEquals("↩ to schedule", session.submitHint("↩").load())
    // The Start chip says when, so the outcome line does not repeat it.
    assertNull(session.outcome)
  }

  @Test
  fun primaryLabel_batchWithATorrent_countsItems() = runTest {
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Show"
    val api = IntakeTestApi(check = { url, _ -> if (url == magnet) torrent(url) else ready(url) })
    val session = session(api, IntakeRequest("${links.first()}\n$magnet"))

    val label = session.primaryLabel.load()
    assertTrue(label.startsWith("Download 2 items · "), label)
  }

  @Test
  fun primaryLabel_onlyDuplicates_saysNothingToAdd() = runTest {
    val api = IntakeTestApi()
    api.base.add(DownloadState.Queued, DownloadRequest(links.first()))
    val session = session(api, IntakeRequest(links.first()))

    assertEquals("Nothing to add", session.primaryLabel.load())
    assertFalse(session.canSubmit)
  }

  @Test
  fun middleEllipsis_longName_keepsBothEnds() {
    val name = "ubuntu-24.04.1-live-server-amd64-with-extras.iso"

    val short = middleEllipsis(name, 20)

    assertEquals(20, short.length)
    assertEquals("ubuntu-24.…xtras.iso", short)
    assertEquals("short.iso", middleEllipsis("short.iso", 20))
  }

  @Test
  fun showsOptions_emptyOrTextWithoutLinks_staysHidden() = runTest {
    val session = session(IntakeTestApi(), IntakeRequest())

    assertFalse(session.showsOptions)
    session.onTextChange(TextFieldValue("ubuntu desktop iso"))
    advanceTimeBy(IntakeSession.TYPING_DEBOUNCE + 1.milliseconds)
    runCurrent()
    assertFalse(session.showsOptions)
    session.onTextChange(TextFieldValue(links.first()))
    runCurrent()

    assertTrue(session.showsOptions)
  }

  @Test
  fun showsOptions_editingATask_alwaysShows() = runTest {
    val api = IntakeTestApi()
    val task = api.base.add(DownloadState.Queued, DownloadRequest(links.first()))
    val session = session(api, IntakeRequest(editTask = TaskKey(LOCAL_DEVICE_ID, task.taskId)))

    assertTrue(session.showsOptions)
    assertEquals("Apply changes", session.primaryLabel.load())
    assertEquals("apply", session.submitVerb)
  }

  @Test
  fun isCurl_curlCommand_switchesTheInputToMonospace() = runTest {
    val session = session(IntakeTestApi(), IntakeRequest())

    session.onTextChange(TextFieldValue(links.first()))
    assertFalse(session.isCurl)
    session.onTextChange(
      TextFieldValue("curl '${links.first()}' -H 'Cookie: session=1' --compressed"),
    )

    assertTrue(session.isCurl)
  }

  @Test
  fun optionValues_defaults_sumUpWithoutChanges() = runTest {
    val session = session(IntakeTestApi(), IntakeRequest(links.first()))

    val values = session.optionValues

    assertEquals(listOf("Unlimited", "Normal", "Now", "Auto"), values.map { it.text }.load())
    assertTrue(values.none { it.changed })
  }

  @Test
  fun intakeOptionValues_changedValues_readAsChips() = runTest {
    val values = intakeOptionValues(
      speedLimit = SpeedLimit.mbps(2),
      priority = DownloadPriority.URGENT,
      schedule = DownloadSchedule.AtTime(Instant.parse("2026-10-01T23:00:00Z")),
      connections = 8,
      torrents = false,
      singleConnection = false,
      now = NOW,
      zone = TimeZone.UTC,
    )

    assertEquals(
      listOf("Max 2 MB/s", "⚡ Urgent", "Starts 23:00 tonight", "8 connections"),
      values.map { it.text }.load(),
    )
    assertTrue(values.all { it.changed })
  }

  @Test
  fun intakeOptionValues_retryHighPriorityAndTorrentPeers_readTheirOwnWay() = runTest {
    val values = intakeOptionValues(
      speedLimit = SpeedLimit.Unlimited,
      priority = DownloadPriority.HIGH,
      schedule = null,
      connections = 50,
      torrents = true,
      singleConnection = false,
      now = NOW,
      zone = TimeZone.UTC,
    )

    assertEquals(
      listOf(IntakeOption.Speed, IntakeOption.Priority, IntakeOption.Connections),
      values.map { it.option },
    )
    assertEquals(
      listOf("Unlimited", "High priority", "50 peers"),
      values.map { it.text }.load(),
    )
  }

  @Test
  fun intakeOptionValues_startThatPassed_isNotAChange() = runTest {
    val values = intakeOptionValues(
      speedLimit = SpeedLimit.Unlimited,
      priority = DownloadPriority.NORMAL,
      schedule = DownloadSchedule.AtTime(NOW - 1.milliseconds),
      connections = 0,
      torrents = false,
      singleConnection = false,
      now = NOW,
      zone = TimeZone.UTC,
    )

    val start = values[2]
    assertEquals(IntakeOption.Start, start.option)
    assertEquals("Now", start.text.load())
    assertFalse(start.changed)
  }

  @Test
  fun intakeOptionValues_serverWithOneConnection_isNotAChange() = runTest {
    val values = intakeOptionValues(
      speedLimit = SpeedLimit.Unlimited,
      priority = DownloadPriority.NORMAL,
      schedule = DownloadSchedule.Immediate,
      connections = 4,
      torrents = false,
      singleConnection = true,
      now = NOW,
      zone = TimeZone.UTC,
    )

    val connections = values.single { it.option == IntakeOption.Connections }
    assertEquals("1 connection", connections.text.load())
    assertFalse(connections.changed)
  }

  @Test
  fun resetOption_changedValues_goBackToTheirDefaults() = runTest {
    val session = session(IntakeTestApi(), IntakeRequest(links.first()))
    session.speedLimit = SpeedLimit.mbps(2)
    session.priority = DownloadPriority.LOW
    session.schedule = DownloadSchedule.AtTime(Instant.parse("2030-01-01T01:00:00Z"))
    session.connections = 4

    IntakeOption.entries.forEach(session::resetOption)

    assertTrue(session.optionValues.none { it.changed })
  }

  @Test
  fun offersClipboard_afterTheSheetWasFilledFromIt_stopsOffering() = runTest {
    val session = session(IntakeTestApi(), IntakeRequest())
    val clip = links.first()

    assertTrue(session.offersClipboard(clipHash(clip)))
    assertTrue(session.offersClipboard(null))
    assertTrue(session.offerClipboard(clip))
    assertFalse(session.offersClipboard(clipHash(clip)))
    session.dismissClipboard()
    runCurrent()

    assertFalse(session.offersClipboard(clipHash(clip)))
    assertFalse(session.offersClipboard(null))
    assertTrue(session.offersClipboard(clipHash(links[1])))
  }

  @Test
  fun pasteClipboard_clipOfferedBefore_stillFillsTheSheet() = runTest {
    val clip = links.first()
    val config = KetchConfig(ui = UiPreferences(lastClipHash = clipHash(clip)))
    val session = stateAndSession(IntakeTestApi(), IntakeRequest(), config).second

    assertFalse(session.offerClipboard(clip))
    session.pasteClipboard(clip)
    runCurrent()

    assertEquals(clip, session.text.text)
    assertTrue(session.fromClipboard)
    assertEquals(1, session.entries.size)
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
    assertTrue(toast.title.load().endsWith("· 1 failed"), toast.title.load())
    assertEquals("Review", toast.actions.first().label.load())
  }

  @Test
  fun review_partOfARangeFailed_reopensOnlyThatPart() = runTest {
    val api = IntakeTestApi()
    val parts = (1..3).map { "https://cdn.example.com/set/part$it.rar" }
    api.base.downloadFailure = { if (it.url == parts[1]) KetchError.Network() else null }
    val (state, session) = stateAndSession(
      api,
      IntakeRequest("https://cdn.example.com/set/part[1-3].rar"),
    )

    session.submit {}
    runCurrent()
    val review = state.messages.active.value.last().actions.first()
    assertEquals("Review", review.label.load())
    review.onClick()

    assertEquals(listOf(parts[1]), state.intakeRequest?.seeds?.map { it.url })
  }

  @Test
  fun submit_oneCheckFails_keepsItOutAndAddsTheRest() = runTest {
    val api = IntakeTestApi(check = { url, _ ->
      if (url == links[0]) throw KetchError.Http(404) else ready(url)
    })
    val session = session(api, IntakeRequest(links.joinToString("\n")))

    assertEquals(1, session.summary.attention)
    assertEquals("Download 4 files · 3.8 MB", session.primaryLabel.load())
    session.submit {}
    runCurrent()

    assertEquals(links.drop(1), api.base.requests.map { it.url })
  }

  @Test
  fun submit_linksStillChecking_waitsAndLeavesOutTheOneThatFails() = runTest {
    val gate = CompletableDeferred<Unit>()
    val api = IntakeTestApi(check = { url, _ ->
      gate.await()
      if (url == links[1]) throw KetchError.Http(404) else ready(url)
    })
    val session = session(api, IntakeRequest(links.take(3).joinToString("\n")))
    var closed = false

    session.submit { closed = true }
    runCurrent()
    assertTrue(api.base.requests.isEmpty())
    assertFalse(session.canSubmit)
    gate.complete(Unit)
    runCurrent()

    assertTrue(closed)
    assertEquals(listOf(links[0], links[2]), api.base.requests.map { it.url })
  }

  @Test
  fun submit_checkThatNeverAnswers_addsTheLinkAfterTheWait() = runTest {
    val api = IntakeTestApi(check = { _, _ -> CompletableDeferred<ResolvedSource>().await() })
    val session = session(api, IntakeRequest(links.first()))

    session.submit {}
    runCurrent()
    assertTrue(api.base.requests.isEmpty())
    advanceTimeBy(IntakeSession.SUBMIT_CHECK_WAIT.inWholeMilliseconds + 1)
    runCurrent()

    assertEquals(listOf(links.first()), api.base.requests.map { it.url })
  }

  @Test
  fun name_seedFileName_winsOverTheCheckedName() = runTest {
    val seed = IntakeSeed(url = "https://files.example.com/get?id=7", fileName = "report.pdf")
    val session = session(IntakeTestApi(), IntakeRequest(seeds = listOf(seed)))

    assertEquals("report.pdf", session.entries.single().name)
  }

  @Test
  fun summaryText_severalProblems_agreesInNumber() = runTest {
    assertEquals(
      "2 links · 1 ready · 1 needs attention",
      IntakeSummary(2, 1, 0, 1, 0, 0).text.load(),
    )
    assertEquals(
      "3 links · 1 ready · 2 need attention",
      IntakeSummary(3, 1, 0, 2, 0, 0).text.load(),
    )
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

    assertEquals("Waiting for file list", session.primaryLabel.load())
    assertFalse(session.canSubmit)
    metadata.complete(torrent(magnet))
    runCurrent()

    assertTrue(session.canSubmit)
    val entry = session.entries.single()
    assertEquals(entry, session.torrentStage)
    assertEquals(setOf("0", "1"), entry.selectedFiles)
    assertEquals("Download 2 files · 2.0 KB", session.primaryLabel.load())
  }

  @Test
  fun addAnyway_magnetOnSupportingDevice_awaitsFiles() = runTest {
    val metadata = CompletableDeferred<ResolvedSource>()
    val api = IntakeTestApi(
      check = { _, _ -> metadata.await() },
      features = setOf(KetchFeatures.TORRENT_AWAIT_FILE_SELECTION),
    )
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Show"
    val session = session(api, IntakeRequest(magnet))

    session.addAllAnyway {}
    runCurrent()

    val request = api.base.requests.single()
    assertTrue(request.awaitFileSelection)
    assertEquals(emptySet(), request.selectedFileIds)
  }

  @Test
  fun addAnyway_olderDevice_downloadsEveryFile() = runTest {
    val metadata = CompletableDeferred<ResolvedSource>()
    val api = IntakeTestApi(check = { _, _ -> metadata.await() })
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Show"
    val session = session(api, IntakeRequest(magnet))

    session.addAllAnyway {}
    runCurrent()

    assertFalse(api.base.requests.single().awaitFileSelection)
  }

  @Test
  fun submit_magnetWithItsFileList_doesNotAwaitFiles() = runTest {
    val api = IntakeTestApi(
      check = { url, _ -> torrent(url) },
      features = setOf(KetchFeatures.TORRENT_AWAIT_FILE_SELECTION),
    )
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Show"
    val session = session(api, IntakeRequest(magnet))

    session.submit {}
    runCurrent()

    assertFalse(api.base.requests.single().awaitFileSelection)
  }

  @Test
  fun torrentSort_chosen_isRememberedForTheNextSheet() = runTest {
    val (state, session) = stateAndSession(IntakeTestApi(), IntakeRequest(links.first()))

    assertEquals(FileSort(FileOrder.Name), session.torrentSort)
    session.torrentSort = FileSort(FileOrder.Size).reverse()

    assertEquals("size:asc", state.appSettings.ui.sort[FileSortSurface.Intake.key])
    assertEquals(FileSort(FileOrder.Size, descending = false), session.torrentSort)
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
    assertEquals("Retry", session.primaryLabel.load())
    session.submit {}
    runCurrent()

    assertEquals(listOf("priority HIGH", "resume"), failed.calls)
    assertTrue(api.base.requests.isEmpty())
  }

  @Test
  fun retry_autoConnectionsOnOlderDevice_setsTheDeviceDefault() = runTest {
    val api = IntakeTestApi()
    val failed = api.base.add(
      DownloadState.Failed(KetchError.Network()),
      DownloadRequest(links.first(), connections = 8),
    )
    val request = IntakeRequest(
      seeds = listOf(IntakeSeed(links.first())),
      retryOf = TaskKey(LOCAL_DEVICE_ID, failed.taskId),
    )
    val session = session(api, request)
    runCurrent()
    session.connections = 0

    assertFalse(session.startsOver)
    session.submit {}
    runCurrent()

    assertEquals(listOf("connections 4", "resume"), failed.calls)
  }

  @Test
  fun categoryFolder_singleMatchingLink_namesItsFolderUntilOneIsChosen() = runTest {
    val video = DownloadCategory(folder = "Media/Video", extensions = listOf("mp4"))
    val api = IntakeTestApi(
      features = KetchFeatures.ALL,
      config = DownloadConfig(categories = listOf(video)),
    )
    val seeds = listOf(IntakeSeed("https://example.com/talk.mp4"))
    val session = session(api, IntakeRequest(seeds = seeds))
    runCurrent()

    assertEquals("Media/Video", session.categoryFolder)
    session.folder = "/Volumes/Data"
    assertNull(session.categoryFolder)
  }

  @Test
  fun categoryFolder_deviceWithoutCategoryFolders_isNull() = runTest {
    val video = DownloadCategory(folder = "Video", extensions = listOf("mp4"))
    val api = IntakeTestApi(config = DownloadConfig(categories = listOf(video)))
    val seeds = listOf(IntakeSeed("https://example.com/talk.mp4"))
    val session = session(api, IntakeRequest(seeds = seeds))
    runCurrent()

    assertNull(session.categoryFolder)
  }

  @Test
  fun retry_autoConnectionsOnCapableDevice_setsAuto() = runTest {
    val api = IntakeTestApi(features = KetchFeatures.ALL)
    val failed = api.base.add(
      DownloadState.Failed(KetchError.Network()),
      DownloadRequest(links.first(), connections = 8),
    )
    val request = IntakeRequest(
      seeds = listOf(IntakeSeed(links.first())),
      retryOf = TaskKey(LOCAL_DEVICE_ID, failed.taskId),
    )
    val session = session(api, request)
    runCurrent()
    session.connections = 0

    session.submit {}
    runCurrent()

    assertEquals(listOf("connections 0", "resume"), failed.calls)
  }

  @Test
  fun apply_autoPeerLimitOnCapableDevice_setsAuto() = runTest {
    val api = IntakeTestApi(features = KetchFeatures.ALL)
    val magnet = "magnet:?xt=urn:btih:0123456789abcdef0123456789abcdef01234567&dn=Show"
    val task = api.base.add(DownloadState.Queued, DownloadRequest(magnet, connections = 40))
    val session = session(api, IntakeRequest(editTask = TaskKey(LOCAL_DEVICE_ID, task.taskId)))
    runCurrent()
    session.connections = 0

    assertTrue(session.torrentsOnly)
    session.submit {}
    runCurrent()

    assertEquals(listOf("connections 0"), task.calls)
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
    assertEquals("Start over", session.primaryLabel.load())
    session.submit {}
    runCurrent()

    assertEquals(listOf("remove deleteFiles=true"), failed.calls)
    assertEquals(mapOf("Cookie" to "session=1"), api.base.requests.single().headers)
  }

  @Test
  fun startsOver_headerNamesInAnotherCase_keepsTheTaskUntilOneChanges() = runTest {
    val api = IntakeTestApi()
    val headers = mapOf("referer" to "https://a.example/", "cookie" to "id=1")
    val failed = api.base.add(
      DownloadState.Failed(KetchError.Http(403)),
      DownloadRequest(links.first(), headers = headers),
    )
    val request = IntakeRequest(
      seeds = listOf(IntakeSeed(links.first(), headers = headers)),
      retryOf = TaskKey(LOCAL_DEVICE_ID, failed.taskId),
    )
    val session = session(api, request)

    assertFalse(session.startsOver)
    session.headers.referer = "https://b.example/"
    assertTrue(session.startsOver)
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
  fun intakeOutcome_freeSlots_startNowWithTheTime() = runTest {
    val config = DownloadConfig(maxConcurrentDownloads = 2)
    val running = listOf(task("a", downloading(speed = 1_000_000)))
    val outcome =
      outcome(listOf("a.example"), DownloadPriority.NORMAL, config, running, 240_000_000)

    assertEquals("Starts now · 1 of 2 slots free · ≈ 4 min at current speed", outcome)
  }

  @Test
  fun intakeOutcome_lessThanAMinuteLeft_saysSoWithoutApproximating() = runTest {
    val config = DownloadConfig(maxConcurrentDownloads = 2)
    val running = listOf(task("a", downloading(speed = 1_000_000)))
    val outcome = outcome(listOf("a.example"), DownloadPriority.NORMAL, config, running, 30_000_000)

    assertEquals(
      "Starts now · 1 of 2 slots free · under a minute at current speed",
      outcome,
    )
  }

  @Test
  fun intakeOutcome_slotsFull_isQueuedBehindTheWaitingOnes() = runTest {
    val config = DownloadConfig(maxConcurrentDownloads = 1)
    val tasks = listOf(
      task("a", downloading()),
      task("b", DownloadState.Queued),
      task("c", DownloadState.Queued, DownloadPriority.LOW),
    )

    val outcome = outcome(listOf("x.example"), DownloadPriority.NORMAL, config, tasks)

    assertEquals("Queued · 1 ahead", outcome)
  }

  @Test
  fun intakeOutcome_slotsFullWithNothingWaiting_isNextInLine() = runTest {
    val config = DownloadConfig(maxConcurrentDownloads = 1)
    val tasks = listOf(task("a", downloading()))

    val outcome = outcome(listOf("x.example"), DownloadPriority.NORMAL, config, tasks)

    assertEquals("Queued · next in line", outcome)
  }

  @Test
  fun intakeOutcome_urgentWithFullSlots_namesTheTaskItPauses() = runTest {
    val config = DownloadConfig(maxConcurrentDownloads = 1)
    val tasks = listOf(task("debian-12.iso", downloading(), DownloadPriority.LOW))

    val outcome = outcome(listOf("x.example"), DownloadPriority.URGENT, config, tasks)

    assertEquals("⚡ Urgent: starts now and pauses debian-12.iso (Low)", outcome)
  }

  @Test
  fun intakeOutcome_hostFull_waitsForTheServer() = runTest {
    val config = DownloadConfig(maxConcurrentDownloads = 4, maxConnectionsPerHost = 1)
    val tasks = listOf(task("a", downloading(), host = "github.com"))

    val outcome = outcome(listOf("github.com"), DownloadPriority.NORMAL, config, tasks)

    assertEquals("Waits for github.com · 1 per server", outcome)
  }

  @Test
  fun intakeOutcome_batch_splitsStartingAndQueued() = runTest {
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

  private suspend fun outcome(
    hosts: List<String?>,
    priority: DownloadPriority,
    config: DownloadConfig,
    tasks: List<ListTestTask>,
    bytes: Long? = null,
  ): String? = intakeOutcome(
    hosts = hosts,
    priority = priority,
    schedule = DownloadSchedule.Immediate,
    config = config,
    tasks = tasks,
    bytes = bytes,
    now = NOW,
    zone = TimeZone.UTC,
  ).load()

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
  private val features: Set<String> = emptySet(),
  private val config: DownloadConfig = DownloadConfig(maxConcurrentDownloads = 3),
) : KetchApi by base {
  override suspend fun resolve(url: String, properties: Map<String, String>): ResolvedSource =
    check(url, properties)

  override suspend fun status(): KetchStatus = testStatus(
    name = "This Mac",
    config = config,
    version = "1",
    revision = "r",
    system = testSystem(
      os = "Mac OS X",
      arch = "aarch64",
      javaVersion = "21",
      availableProcessors = 8,
      downloadDirectory = "/Users/me/Downloads",
      totalSpace = 1L shl 40,
      freeSpace = 1L shl 39,
      usableSpace = 1L shl 39,
    ),
    features = features,
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
