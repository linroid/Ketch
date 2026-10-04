package com.linroid.ketch.app.state

import androidx.compose.ui.text.input.TextFieldValue
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.app.FakeAiProvider
import com.linroid.ketch.app.applySnapshotChanges
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import com.linroid.ketch.config.PageAccessMode
import com.linroid.ketch.config.PageAccessSettings
import com.linroid.ketch.config.SiteNames
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Instant

class AiDiscoverControllerTest {

  private fun candidate(name: String) = AiCandidate(
    url = "https://example.com/$name",
    title = name,
    sourceUrl = "https://example.com/downloads",
    confidence = 0.9f,
    description = "",
  )

  private fun page(host: String, path: String = "/") =
    AiPageRequest(url = "https://$host$path", host = host, kind = AiPageKind.Page)

  private fun settingsWith(
    provider: AiDiscoveryProvider,
    access: PageAccessSettings = PageAccessSettings(),
  ): AiSettingsController =
    AiSettingsController(factory = { provider }).apply {
      save(AiSettings(enabled = true, access = access))
    }

  private fun TestScope.controller(
    settings: AiSettingsController,
    history: DiscoverHistoryStore = InMemoryDiscoverHistoryStore(),
  ): AiDiscoverController {
    var ids = 0
    return AiDiscoverController(
      aiSettings = settings,
      scope = backgroundScope,
      history = history,
      clock = ListFixtures.clock(this),
      newId = { "id${++ids}" },
    )
  }

  private fun TestScope.controller(
    provider: AiDiscoveryProvider,
    access: PageAccessSettings = PageAccessSettings(),
    history: DiscoverHistoryStore = InMemoryDiscoverHistoryStore(),
  ): AiDiscoverController = controller(settingsWith(provider, access), history)

  /** Types [message], and [sites] when given, into the composer and sends it. */
  private fun AiDiscoverController.say(message: String, sites: String? = null) {
    draft.text = TextFieldValue(message)
    if (sites != null) draft.sites = sites
    send()
  }

  private val AiDiscoverController.turn: DiscoverTurn
    get() = assertNotNull(current).turns.last()

  /** A finished session that ran at [updatedAt], with one turn that found [found]. */
  private fun savedSession(
    id: String,
    updatedAt: Instant,
    sites: List<String> = emptyList(),
    status: TurnStatus = TurnStatus.Done,
    found: List<AiCandidate> = emptyList(),
  ) = DiscoverSession(
    id = id,
    title = id,
    createdAt = updatedAt,
    updatedAt = updatedAt,
    turns = listOf(
      DiscoverTurn(
        id = "$id-turn",
        message = id,
        sites = sites,
        startedAt = updatedAt,
        status = status,
        candidates = found,
      ),
    ),
  )

  @Test
  fun add_oneCandidateFails_addsTheOthers() = runTest {
    val api = RecordingKetchApi().apply {
      downloadFailure = { if ("broken" in it.url) IllegalStateException("Not found") else null }
    }
    val controller = AiDiscoverController(AiSettingsController(), backgroundScope)
    val broken = candidate("broken.iso")

    val result = controller.add(
      api = api,
      candidates = listOf(candidate("a.iso"), broken, candidate("b.iso")),
      query = "ubuntu iso",
    )

    assertEquals(
      listOf("https://example.com/a.iso", "https://example.com/b.iso"),
      api.requests.map { it.url },
    )
    assertEquals(listOf(broken), result.failed.map { it.first })
    assertEquals(2, result.added.size)
  }

  @Test
  fun add_candidate_recordsWhereItCameFrom() = runTest {
    val api = RecordingKetchApi()
    val controller = AiDiscoverController(AiSettingsController(), backgroundScope)

    controller.add(api, listOf(candidate("a.iso")), query = "ubuntu iso")

    val request = api.requests.single()
    assertEquals("https://example.com/downloads", request.headers["Referer"])
    assertEquals("discover", request.properties["ketch.origin"])
    assertEquals("ubuntu iso", request.properties["ketch.query"])
  }

  @Test
  fun add_withoutAQuery_recordsTheFirstMessageNotTheAgentsTitle() = runTest {
    val api = RecordingKetchApi()
    val provider = FakeAiProvider(
      candidates = listOf(candidate("a.iso")),
      title = "Ubuntu Server 24.04 LTS",
    )
    val controller = controller(provider)
    controller.say("Ubuntu server\nthe LTS one")
    runCurrent()
    controller.say("only arm64")
    runCurrent()

    controller.add(api, listOf(candidate("a.iso")))
    val seed = controller.reviewRequest(listOf(candidate("a.iso")), null).seeds.single()

    assertEquals("Ubuntu Server 24.04 LTS", assertNotNull(controller.current).title)
    assertEquals("Ubuntu server", api.requests.single().properties["ketch.query"])
    assertEquals("Ubuntu server", seed.properties["ketch.query"])
  }

  @Test
  fun reviewRequest_candidates_seedTheAddSheetWithTheirOrigin() = runTest {
    val controller = AiDiscoverController(AiSettingsController(), backgroundScope)
    val named = candidate("a.iso").copy(fileName = "a.iso")

    val request = controller.reviewRequest(listOf(named), "nas.local:8642", query = "ubuntu iso")

    val seed = request.seeds.single()
    assertEquals("nas.local:8642", request.targetDeviceId)
    assertEquals("https://example.com/a.iso", seed.url)
    assertEquals("a.iso", seed.fileName)
    assertEquals("https://example.com/downloads", seed.headers["Referer"])
    assertEquals("discover", seed.properties["ketch.origin"])
    assertEquals("ubuntu iso", seed.properties["ketch.query"])
  }

  @Test
  fun send_firstMessage_startsASessionAndKeepsTheWebsites() = runTest {
    val provider = FakeAiProvider(candidates = listOf(candidate("blender.dmg")), summary = "One")
    val history = InMemoryDiscoverHistoryStore()
    val controller = controller(provider, history = history)

    controller.say("  Blender   for Apple silicon \n arm64 only", sites = "blender.org")
    runCurrent()

    val session = assertNotNull(controller.current)
    assertEquals("Blender for Apple silicon", session.title)
    assertEquals(TurnStatus.Done, controller.turn.status)
    assertEquals("One", controller.turn.summary)
    assertEquals(listOf("blender.org"), provider.requests.single().sites)
    assertEquals("", controller.draft.text.text)
    assertEquals("blender.org", controller.draft.sites)
    assertEquals(controller.sessions, history.load())
  }

  @Test
  fun send_firstTurnEnds_takesTheAgentsTitle() = runTest {
    val provider = FakeAiProvider(title = "Blender 4.2 for Apple silicon", gated = true)
    val history = InMemoryDiscoverHistoryStore()
    val controller = controller(provider, history = history)

    controller.say("blender for my  m2 mac\nthe newest one")
    runCurrent()
    // The message names the session while the agent works.
    assertEquals("blender for my m2 mac", assertNotNull(controller.current).title)
    provider.gates.single().complete(Unit)
    runCurrent()

    assertEquals("Blender 4.2 for Apple silicon", assertNotNull(controller.current).title)
    assertEquals("Blender 4.2 for Apple silicon", history.load().single().title)
  }

  @Test
  fun send_followUp_neverRenamesTheSession() = runTest {
    val provider = FakeAiProvider(title = "Blender 4.2 for Apple silicon")
    val controller = controller(provider)
    controller.say("blender for my m2 mac")
    runCurrent()

    provider.title = "Blender arm64 builds"
    controller.say("only arm64")
    runCurrent()

    assertEquals("Blender 4.2 for Apple silicon", assertNotNull(controller.current).title)
  }

  @Test
  fun send_firstTurnWithoutATitle_keepsTheMessageAfterFollowUps() = runTest {
    val provider = FakeAiProvider(title = "  ")
    val controller = controller(provider)
    controller.say("blender for my m2 mac")
    runCurrent()
    assertEquals("blender for my m2 mac", assertNotNull(controller.current).title)

    provider.title = "Blender arm64 builds"
    controller.say("only arm64")
    runCurrent()

    assertEquals("blender for my m2 mac", assertNotNull(controller.current).title)
  }

  @Test
  fun retry_stoppedFirstTurn_takesTheAgentsTitle() = runTest {
    val provider = FakeAiProvider(title = "Blender 4.2 for Apple silicon", gated = true)
    val controller = controller(provider)
    controller.say("blender for my m2 mac")
    runCurrent()
    controller.stop()

    controller.retry()
    runCurrent()
    provider.gates[1].complete(Unit)
    runCurrent()

    assertEquals("Blender 4.2 for Apple silicon", assertNotNull(controller.current).title)
  }

  @Test
  fun searchEverywhere_namedFirstTurn_keepsItsTitle() = runTest {
    val provider = FakeAiProvider(title = "Blender 4.2 for Apple silicon")
    val controller = controller(provider)
    controller.say("blender for my m2 mac", sites = "blender.org")
    runCurrent()

    provider.title = "Blender from anywhere"
    controller.searchEverywhere()
    runCurrent()

    assertEquals(2, provider.requests.size)
    assertEquals("Blender 4.2 for Apple silicon", assertNotNull(controller.current).title)
  }

  @Test
  fun init_savedNamedSession_keepsItsTitle() = runTest {
    val saved = savedSession("s1", Instant.parse("2026-10-01T10:00:00Z"))
      .copy(title = "Blender 4.2 for Apple silicon")
    val history = InMemoryDiscoverHistoryStore(listOf(saved))

    val controller = controller(FakeAiProvider(), history = history)

    val session = controller.sessions.single()
    assertEquals("Blender 4.2 for Apple silicon", session.title)
    assertEquals("s1", session.query)
  }

  @Test
  fun send_blankText_doesNothing() = runTest {
    val provider = FakeAiProvider()
    val controller = controller(provider)

    controller.say("   ")
    runCurrent()

    assertTrue(provider.requests.isEmpty())
    assertNull(controller.current)
  }

  @Test
  fun send_whileTheSessionRuns_keepsTheText() = runTest {
    val provider = FakeAiProvider(gated = true)
    val controller = controller(provider)
    controller.say("blender")
    runCurrent()

    controller.say("only arm64")
    runCurrent()

    assertEquals(1, provider.requests.size)
    assertEquals(1, assertNotNull(controller.current).turns.size)
    assertEquals("only arm64", controller.draft.text.text)
  }

  @Test
  fun send_followUp_sendsEveryEarlierTurnWithResultsOnlyFromFinishedOnes() = runTest {
    val (a, b) = candidate("a.dmg") to candidate("b.dmg")
    val provider = FakeAiProvider(candidates = listOf(a, b), gated = true)
    val controller = controller(provider)
    controller.say("blender", sites = "blender.org")
    runCurrent()
    controller.stop()
    controller.say("only arm64", sites = "")
    runCurrent()
    provider.gates[1].complete(Unit)
    runCurrent()
    controller.discard(listOf(b.url))

    controller.say("the newest release")
    runCurrent()

    val request = provider.requests.last()
    assertEquals("the newest release", request.query)
    assertEquals(
      listOf(
        AiDiscoverTurn(
          request = "blender",
          sites = listOf("blender.org"),
          completed = false,
          results = emptyList(),
        ),
        AiDiscoverTurn(
          request = "only arm64",
          sites = emptyList(),
          completed = true,
          results = listOf(a),
        ),
      ),
      request.history,
    )
    assertEquals(setOf(SiteNames.canonicalUrl(b.url)), request.excludedUrls)
  }

  @Test
  fun send_longSession_sendsTheFirstAndTheLatestFiveTurns() = runTest {
    val provider = FakeAiProvider()
    val controller = controller(provider)

    for (n in 1..8) {
      controller.say("m$n")
      runCurrent()
    }

    assertEquals(
      listOf("m1", "m3", "m4", "m5", "m6", "m7"),
      provider.requests.last().history.map { it.request },
    )
  }

  @Test
  fun send_discardedLinkFoundAgain_staysHidden() = runTest {
    val (a, b) = candidate("a.dmg") to candidate("b.dmg")
    val controller = controller(FakeAiProvider(candidates = listOf(a, b)))
    controller.say("blender")
    runCurrent()
    controller.discard(listOf(a.url))

    controller.say("again")
    runCurrent()

    assertEquals(listOf(b), controller.turn.candidates)
  }

  @Test
  fun send_followUpReturnsAnEarlierLink_keepsWhatTheEarlierTurnKnew() = runTest {
    val checked = candidate("a.dmg").copy(
      fileName = "a.dmg",
      fileSize = 2048L,
      mimeType = "application/x-apple-diskimage",
      description = "The arm64 installer",
    )
    val bare = AiCandidate(url = checked.url, title = "Latest", confidence = 0.8f, description = "")
    val replies = ArrayDeque(listOf(listOf(checked, candidate("b.dmg")), listOf(bare)))
    val provider = object : AiDiscoveryProvider {
      override suspend fun discover(
        request: AiDiscoverRequest,
        onStep: (DiscoveryStep) -> Unit,
        approve: suspend (AiPageRequest) -> Boolean,
      ) = AiDiscoverResponse(request.query, replies.removeFirst())

      override suspend fun verify() = "OK"
    }
    val controller = controller(provider)
    controller.say("blender")
    runCurrent()

    controller.say("just the latest version")
    runCurrent()

    val expected = checked.copy(title = "Latest", confidence = 0.8f)
    assertEquals(listOf(expected), controller.turn.candidates)
  }

  @Test
  fun carryOver_earlierLink_fillsOnlyWhatTheNewCandidateLacks() {
    val earlier = candidate("a.dmg").copy(
      fileName = "a.dmg",
      fileSize = 2048L,
      mimeType = "application/x-apple-diskimage",
      description = "Old description",
    )
    val found = AiCandidate(
      url = earlier.url,
      title = "Blender",
      fileName = "renamed.dmg",
      fileSize = 4096L,
      sourceUrl = "https://example.com/new",
      confidence = 0.5f,
      description = "New description",
    )

    val result = carryOver(listOf(found), listOf(earlier))

    assertEquals(listOf(found.copy(mimeType = "application/x-apple-diskimage")), result)
  }

  @Test
  fun carryOver_differentSpellingOfTheSameLink_matchesTheLatestEarlierResult() {
    val older = candidate("a.dmg").copy(fileSize = 1L, description = "Older")
    val newer = AiCandidate(
      url = "HTTPS://Example.com:443/a.dmg#download",
      title = "Newer",
      fileSize = 2L,
      confidence = 0.9f,
      description = "",
    )
    val found = AiCandidate(
      url = "https://example.com/a.dmg",
      title = "A",
      confidence = 0.7f,
      description = "",
    )
    val other = candidate("b.dmg")

    val result = carryOver(listOf(found, other), listOf(older, newer))

    assertEquals(listOf(found.copy(fileSize = 2L), other), result)
  }

  @Test
  fun discard_selectedResult_hidesAndUnselectsItUntilRestored() = runTest {
    val (a, b) = candidate("a.dmg") to candidate("b.dmg")
    val controller = controller(FakeAiProvider(candidates = listOf(a, b)))
    controller.say("blender")
    runCurrent()
    controller.selectAll(controller.turn.id)

    val discarded = controller.discard(listOf("HTTPS://Example.com:443/a.dmg#top"))

    val session = assertNotNull(controller.current)
    assertEquals(setOf(SiteNames.canonicalUrl(a.url)), discarded)
    assertEquals(listOf(b), session.visible(controller.turn))
    assertEquals(listOf(a), session.discardedIn(controller.turn))
    assertEquals(setOf(b.url), controller.selected)
    controller.restore(discarded)
    assertEquals(listOf(a, b), assertNotNull(controller.current).visible(controller.turn))
  }

  @Test
  fun selectedCandidates_linkSpelledDifferentlyInALaterTurn_returnsTheSelectedOne() = runTest {
    val first = candidate("a.dmg")
    val again = first.copy(url = "https://EXAMPLE.com/a.dmg")
    val saved = savedSession("blender", ListFixtures.START, found = listOf(first)).let {
      it.copy(turns = it.turns + it.turns.single().copy(id = "later", candidates = listOf(again)))
    }
    val history = InMemoryDiscoverHistoryStore(listOf(saved))
    val controller = controller(FakeAiProvider(), history = history)
    controller.open(saved.id)

    controller.toggle(again)

    assertEquals(listOf(again), controller.selectedCandidates())
  }

  @Test
  fun found_onlyTurnsThatEndWithResults() = runTest {
    val found = mutableListOf<DiscoverFound>()
    val finding = controller(FakeAiProvider(candidates = listOf(candidate("a"), candidate("b"))))
    val empty = controller(FakeAiProvider())
    val failing = controller(FakeAiProvider(failure = IllegalStateException("Rate limited")))
    for (controller in listOf(finding, empty, failing)) {
      backgroundScope.launch { controller.found.collect { found += it } }
    }
    runCurrent()

    finding.say("archives")
    empty.say("nothing")
    failing.say("broken")
    runCurrent()

    val session = assertNotNull(finding.current)
    assertEquals(listOf(DiscoverFound(session.id, session.turns.single().id, 2)), found)
  }

  @Test
  fun retry_failedNewestTurn_runsItAgainInPlace() = runTest {
    val provider = FakeAiProvider(failure = IllegalStateException("Rate limited"))
    val controller = controller(provider)
    controller.say("blender", sites = "blender.org")
    runCurrent()
    val failed = controller.turn
    assertEquals("Rate limited", failed.errorText)

    controller.retry()
    runCurrent()

    assertEquals(2, provider.requests.size)
    assertEquals(listOf("blender.org"), provider.requests.last().sites)
    assertEquals(failed.id, assertNotNull(controller.current).turns.single().id)
  }

  @Test
  fun retry_newestTurnDone_doesNothing() = runTest {
    val provider = FakeAiProvider()
    val controller = controller(provider)
    controller.say("blender")
    runCurrent()

    controller.retry()
    runCurrent()

    assertEquals(1, provider.requests.size)
  }

  @Test
  fun searchEverywhere_limitedTurn_runsItAgainWithoutItsWebsites() = runTest {
    val provider = FakeAiProvider()
    val controller = controller(provider)
    controller.say("blender", sites = "blender.org")
    runCurrent()

    controller.searchEverywhere()
    runCurrent()

    assertEquals(emptyList(), provider.requests.last().sites)
    assertEquals(emptyList(), controller.turn.sites)
    assertEquals("", controller.draft.sites)
  }

  @Test
  fun steps_reportedWhileRunning_landInOrderBeforeTheResult() = runTest {
    val steps = listOf(DiscoveryStep("Understanding"), DiscoveryStep("Plan", "Search twice"))
    val provider = FakeAiProvider(steps = steps, gated = true)
    val controller = controller(provider)

    controller.say("blender")
    runCurrent()

    assertEquals(TurnStatus.Running, controller.turn.status)
    assertEquals(steps, controller.turn.steps)
    provider.gates.single().complete(Unit)
    runCurrent()
    assertEquals(TurnStatus.Done, controller.turn.status)
    assertEquals(steps, controller.turn.steps)
  }

  @Test
  fun failure_showsTheFullMessageAndSavesTheBrief() = runTest {
    val failure = AiDiscoverFailure(
      message = "The provider rejected the key: invalid key sk-123",
      brief = "The provider rejected the key (HTTP 401)",
    )
    val history = InMemoryDiscoverHistoryStore()
    val controller = controller(FakeAiProvider(failure = failure), history = history)

    controller.say("blender")
    runCurrent()

    assertEquals(TurnStatus.Failed, controller.turn.status)
    assertEquals(failure.message, controller.turn.errorText)
    assertEquals(failure.brief, history.load().single().turns.single().errorText)
  }

  @Test
  fun failure_logsOnlyTheBrief() = runTest {
    val records = mutableListOf<String>()
    val failure = AiDiscoverFailure(
      message = "The provider rejected the key: invalid key sk-123",
      brief = "The provider rejected the key (HTTP 401)",
      cause = IllegalStateException("""{"error": "invalid key sk-123"}"""),
    )
    val controller = controller(FakeAiProvider(failure = failure))

    KetchLogger.setLogger(RecordingLogger(records))
    try {
      controller.say("blender")
      runCurrent()
    } finally {
      KetchLogger.setLogger(Logger.None)
    }

    assertTrue(records.any { failure.brief in it }, records.joinToString("\n"))
    assertTrue(records.none { "sk-123" in it }, records.joinToString("\n"))
  }

  @Test
  fun failure_providerThrowsAnError_endsTheTurnAndFreesItsSlot() = runTest {
    // Such as a reflection failure in a shrunk build.
    val crashing = FakeAiProvider(failure = NotImplementedError())
    val controller = controller(crashing)
    repeat(AiDiscoverController.MAX_RUNNING) {
      controller.newSession()
      controller.say("crash $it")
    }
    runCurrent()

    assertTrue(controller.sessions.none { it.running })
    assertTrue(controller.sessions.all { it.turns.single().status == TurnStatus.Failed })
    assertNull(controller.turn.errorText, "the Error's own text means nothing to the user")
    controller.say("again")
    runCurrent()
    assertEquals(2, assertNotNull(controller.current).turns.size)
    assertEquals(AiDiscoverController.MAX_RUNNING + 1, crashing.requests.size)
  }

  @Test
  fun retry_discoverySwitchedOff_keepsTheFailure() = runTest {
    val provider = FakeAiProvider(failure = IllegalStateException("Rate limited"))
    val settings = settingsWith(provider)
    val controller = controller(settings)
    controller.say("blender")
    runCurrent()
    settings.save(settings.settings.copy(enabled = false))

    controller.retry()
    runCurrent()

    assertEquals(TurnStatus.Failed, controller.turn.status)
    assertEquals("Rate limited", controller.turn.errorText)
    assertEquals(1, provider.requests.size)
  }

  @Test
  fun stop_waitingForAnApproval_declinesItWithoutANote() = runTest {
    val provider = FakeAiProvider(pages = listOf(page("download.blender.org")))
    val controller = controller(provider)
    controller.say("blender")
    runCurrent()
    assertEquals(1, controller.approvals.size)

    controller.stop()
    runCurrent()

    assertEquals(TurnStatus.Stopped, controller.turn.status)
    assertTrue(controller.approvals.isEmpty())
    assertTrue(controller.turn.access.isEmpty())
    assertFalse(assertNotNull(controller.current).running)
  }

  @Test
  fun stop_thenRetryBeforeTheOldRunEnds_ignoresTheOldRun() = runTest {
    val oldGate = CompletableDeferred<Unit>()
    var calls = 0
    val provider = object : AiDiscoveryProvider {
      override suspend fun discover(
        request: AiDiscoverRequest,
        onStep: (DiscoveryStep) -> Unit,
        approve: suspend (AiPageRequest) -> Boolean,
      ): AiDiscoverResponse {
        if (++calls == 1) {
          // A run that only notices it was stopped once its request returns.
          withContext(NonCancellable) { oldGate.await() }
          onStep(DiscoveryStep("Late step"))
          return AiDiscoverResponse(request.query, listOf(candidate("old.dmg")))
        }
        awaitCancellation()
      }

      override suspend fun verify(): String = "OK"
    }
    val controller = controller(provider)
    controller.say("blender")
    runCurrent()

    controller.stop()
    controller.retry()
    runCurrent()
    oldGate.complete(Unit)
    runCurrent()

    assertEquals(TurnStatus.Running, controller.turn.status)
    assertTrue(controller.turn.steps.isEmpty())
    controller.stop()
    assertEquals(TurnStatus.Stopped, controller.turn.status)
    assertTrue(controller.turn.candidates.isEmpty())
  }

  @Test
  fun delete_runningSession_stopsItAndShowsANewOneUntilUndone() = runTest {
    val controller = controller(FakeAiProvider(gated = true))
    controller.say("blender")
    runCurrent()
    val id = assertNotNull(controller.currentId)

    val removed = assertNotNull(controller.delete(id))

    assertEquals(TurnStatus.Stopped, removed.turns.single().status)
    assertNull(controller.current)
    assertTrue(controller.sessions.isEmpty())
    controller.reinsert(removed)
    assertEquals(listOf(removed), controller.sessions)
    assertNull(controller.currentId)
  }

  @Test
  fun clearHistory_runningSession_staysAndTheOthersGo() = runTest {
    val provider = FakeAiProvider(gated = true)
    val controller = controller(provider)
    controller.say("blender")
    runCurrent()
    provider.gates.single().complete(Unit)
    runCurrent()
    controller.newSession()
    controller.say("ubuntu")
    runCurrent()

    val removed = controller.clearHistory()

    assertEquals(listOf("blender"), removed.map { it.title })
    assertEquals(listOf("ubuntu"), controller.sessions.map { it.title })
  }

  @Test
  fun send_beyondTheRunningCap_queuesTurnsAndStartsThemInOrder() = runTest {
    val provider = FakeAiProvider(gated = true)
    val controller = controller(provider)
    for (n in 1..5) {
      controller.newSession()
      controller.say("s$n")
    }
    runCurrent()

    assertEquals(listOf("s1", "s2", "s3"), provider.requests.map { it.query })
    val queued = controller.sessions.filter { it.turns.single().status == TurnStatus.Queued }
    assertEquals(listOf("s5", "s4"), queued.map { it.title })
    provider.gates[1].complete(Unit)
    runCurrent()
    assertEquals("s4", provider.requests.last().query)
    controller.stop(controller.sessions.first { it.title == "s1" }.id)
    runCurrent()
    assertEquals("s5", provider.requests.last().query)
  }

  @Test
  fun stop_queuedTurn_neverStartsIt() = runTest {
    val provider = FakeAiProvider(gated = true)
    val history = InMemoryDiscoverHistoryStore()
    val controller = controller(provider, history = history)
    for (n in 1..4) {
      controller.newSession()
      controller.say("s$n")
    }
    runCurrent()
    assertEquals(TurnStatus.Queued, controller.turn.status)

    controller.stop()
    provider.gates.first().complete(Unit)
    runCurrent()

    assertEquals(TurnStatus.Stopped, controller.turn.status)
    assertEquals(listOf("s1", "s2", "s3"), provider.requests.map { it.query })
    val saved = history.load().first { it.id == controller.currentId }
    assertEquals(TurnStatus.Stopped, saved.turns.single().status)
  }

  @Test
  fun close_turnWaitingForAnApproval_declinesItAndSavesItStopped() = runTest {
    val provider = FakeAiProvider(pages = listOf(page("download.blender.org")))
    val history = InMemoryDiscoverHistoryStore()
    val controller = controller(provider, history = history)
    controller.say("blender")
    runCurrent()
    assertEquals(1, controller.approvals.size)

    controller.close()
    runCurrent()

    assertTrue(controller.approvals.isEmpty())
    assertTrue(controller.turn.access.isEmpty())
    assertEquals(TurnStatus.Stopped, history.load().single().turns.single().status)
  }

  @Test
  fun discover_notSetUp_waitsUntilSetUpThenRuns() = runTest {
    val provider = FakeAiProvider(candidates = listOf(candidate("ubuntu.iso")))
    // Switched on by default, but nothing runs until a provider that needs no key is chosen.
    val settings = AiSettingsController(
      factory = { if (it.llm.provider == LlmProvider.Ollama) provider else null },
    )
    val controller = controller(settings)

    controller.discover(DiscoverRequest("ubuntu server iso"))
    runCurrent()

    assertTrue(controller.sessions.isEmpty())
    assertEquals(DiscoverRequest("ubuntu server iso"), controller.pending)
    settings.save(AiSettings(enabled = true, llm = LlmSettings(provider = LlmProvider.Ollama)))
    assertTrue(controller.runPending())
    runCurrent()
    assertNull(controller.pending)
    assertEquals("ubuntu server iso", provider.requests.single().query)
    assertEquals(TurnStatus.Done, controller.turn.status)
  }

  @Test
  fun discover_notSetUpWhileASavedSearchShows_showsTheSetupPageThatSaysItWaits() = runTest {
    val saved = savedSession("ffmpeg 7 static", ListFixtures.START - 1.minutes)
    val settings = AiSettingsController(factory = { null })
    val controller = controller(settings, InMemoryDiscoverHistoryStore(listOf(saved)))
    controller.open(saved.id)

    controller.discover(DiscoverRequest("ubuntu 24.04 iso"))

    assertNull(controller.currentId, "the saved search gives way to the setup page")
    assertEquals(DiscoverRequest("ubuntu 24.04 iso"), controller.pending)
    assertEquals(listOf(saved.id), controller.sessions.map { it.id })
  }

  @Test
  fun switchedOff_runningAndQueuedTurns_stopAndDeclineTheirRequests() = runTest {
    var cancelled = 0
    var started = 0
    val provider = object : AiDiscoveryProvider {
      override suspend fun discover(
        request: AiDiscoverRequest,
        onStep: (DiscoveryStep) -> Unit,
        approve: suspend (AiPageRequest) -> Boolean,
      ): AiDiscoverResponse {
        started++
        try {
          approve(page("download.blender.org"))
          awaitCancellation()
        } finally {
          cancelled++
        }
      }

      override suspend fun verify(): String = "OK"
    }
    val settings = settingsWith(provider)
    val history = InMemoryDiscoverHistoryStore()
    val controller = controller(settings, history)
    for (n in 1..4) {
      controller.newSession()
      controller.say("s$n")
    }
    runCurrent()
    assertEquals(3, controller.approvals.size)

    settings.save(settings.settings.copy(enabled = false))
    applySnapshotChanges()
    runCurrent()

    assertTrue(controller.approvals.isEmpty(), "no request is left waiting")
    assertEquals(3, cancelled, "every running search is cancelled")
    assertEquals(3, started, "the queued turn never starts")
    val statuses = controller.sessions.map { it.turns.single().status }
    assertEquals(List(4) { TurnStatus.Stopped }, statuses)
    assertEquals(statuses, history.load().map { it.turns.single().status })
    assertTrue(controller.sessions.all { it.turns.single().access.isEmpty() })
  }

  @Test
  fun newModel_runningTurn_finishesOnTheEngineItStartedWith() = runTest {
    val provider = FakeAiProvider(gated = true, candidates = listOf(candidate("a.iso")))
    val next = FakeAiProvider()
    val settings = AiSettingsController(
      factory = { if (it.llm.model == NEXT_MODEL) next else provider },
    ).apply { save(AiSettings(enabled = true)) }
    val controller = controller(settings)
    controller.say("blender")
    runCurrent()

    settings.save(settings.settings.copy(llm = LlmSettings(model = NEXT_MODEL)))
    applySnapshotChanges()
    runCurrent()
    assertSame(next, settings.provider)
    assertEquals(TurnStatus.Running, controller.turn.status)
    provider.gates.single().complete(Unit)
    runCurrent()

    assertEquals(TurnStatus.Done, controller.turn.status)
    assertTrue(next.requests.isEmpty())
  }

  @Test
  fun discover_switchedOff_startsNothing() = runTest {
    val settings = AiSettingsController(factory = { null }).apply { setEnabled(false) }
    val controller = controller(settings)

    controller.discover(DiscoverRequest("blender"))

    assertNull(controller.currentId)
    assertNull(controller.pending)
  }

  @Test
  fun discover_unsupported_startsNothing() = runTest {
    val controller = AiDiscoverController(AiSettingsController(), backgroundScope)

    controller.discover(DiscoverRequest("blender"))

    assertTrue(controller.sessions.isEmpty())
    assertNull(controller.pending)
  }

  @Test
  fun discover_request_startsANewSessionWithItsWebsites() = runTest {
    val provider = FakeAiProvider()
    val controller = controller(provider)
    controller.say("ubuntu")
    runCurrent()
    controller.newSession()
    controller.draft.text = TextFieldValue("half typed")

    controller.discover(DiscoverRequest("Blender", listOf("blender.org")))
    runCurrent()

    assertEquals(2, controller.sessions.size)
    assertEquals("Blender", assertNotNull(controller.current).title)
    assertEquals(listOf("blender.org"), provider.requests.last().sites)
    assertEquals("blender.org", controller.draft.sites)
    assertTrue(controller.draft.showSites)
    controller.newSession()
    assertEquals("half typed", controller.draft.text.text)
  }

  @Test
  fun open_savedSession_startsItsDraftWithItsLastWebsitesAndClearsTheSelection() = runTest {
    val start = ListFixtures.START
    val saved = savedSession("blender", start - 1.minutes, sites = listOf("blender.org"))
    val provider = FakeAiProvider(candidates = listOf(candidate("a.iso")))
    val controller = controller(provider, history = InMemoryDiscoverHistoryStore(listOf(saved)))
    controller.say("ubuntu")
    runCurrent()
    controller.selectAll(controller.turn.id)

    controller.open(saved.id)

    assertTrue(controller.selected.isEmpty())
    assertEquals("blender.org", controller.draft.sites)
    assertEquals("", controller.draft.text.text)
  }

  @Test
  fun load_turnsThatWereRunning_readAsStopped() = runTest {
    val start = ListFixtures.START
    val history = InMemoryDiscoverHistoryStore(
      listOf(
        savedSession("a", start, status = TurnStatus.Running),
        savedSession("b", start - 1.minutes, status = TurnStatus.Queued),
      ),
    )

    val controller = controller(FakeAiProvider(), history = history)

    assertEquals(
      listOf(TurnStatus.Stopped, TurnStatus.Stopped),
      controller.sessions.map { it.turns.single().status },
    )
    assertFalse(controller.sessions.any { it.running })
  }

  @Test
  fun cap_moreSessionsThanTheHistoryKeeps_dropsTheOldestButNeverTheShownOrRunning() = runTest {
    val start = ListFixtures.START
    // Saved by a clock ahead of this one, so the new sessions are the oldest.
    val saved = (1..AiDiscoverController.MAX_SESSIONS).map {
      savedSession("s$it", start + it.minutes)
    }
    val controller = controller(
      FakeAiProvider(gated = true),
      history = InMemoryDiscoverHistoryStore(saved),
    )

    controller.discover(DiscoverRequest("running"))
    controller.newSession()
    controller.say("shown")

    val titles = controller.sessions.map { it.title }
    assertEquals(AiDiscoverController.MAX_SESSIONS, titles.size)
    assertTrue("running" in titles && "shown" in titles)
    assertFalse("s1" in titles || "s2" in titles)
  }

  @Test
  fun approve_askPerSite_waitsForTheAnswerAndNotesIt() = runTest {
    val blender = page("download.blender.org")
    val provider = FakeAiProvider(pages = listOf(blender))
    val controller = controller(provider)
    controller.say("blender")
    runCurrent()

    val approval = controller.approvals.single()
    assertEquals(blender, approval.request)
    assertEquals(listOf(approval), controller.waitingIn(approval.sessionId))
    controller.answer(approval.id, PageAccessChoice.AllowOnce)
    runCurrent()

    assertEquals(listOf(blender to true), provider.answers)
    assertEquals(listOf(AccessNote("download.blender.org", allowed = true)), controller.turn.access)
    assertTrue(controller.approvals.isEmpty())
    assertEquals(TurnStatus.Done, controller.turn.status)
  }

  @Test
  fun approve_allowSite_coversTheSiteInThatSessionOnly() = runTest {
    val first = page("download.blender.org", "/a")
    val second = page("download.blender.org", "/b")
    val provider = FakeAiProvider(pages = listOf(first, second))
    val controller = controller(provider)
    controller.say("blender")
    runCurrent()

    controller.answer(controller.approvals.single().id, PageAccessChoice.AllowSite)
    runCurrent()

    assertEquals(listOf(first to true, second to true), provider.answers)
    controller.newSession()
    controller.say("blender again")
    runCurrent()
    assertEquals(first, controller.approvals.single().request)
  }

  @Test
  fun approve_deny_declinesTheSiteForTheRestOfTheSessionWithoutAsking() = runTest {
    val first = page("mirror.example.edu", "/a")
    val second = page("mirror.example.edu", "/b")
    val provider = FakeAiProvider(pages = listOf(first, second))
    val controller = controller(provider)
    controller.say("blender")
    runCurrent()

    controller.answer(controller.approvals.single().id, PageAccessChoice.Deny)
    runCurrent()

    assertEquals(listOf(first to false, second to false), provider.answers)
    assertEquals(listOf(AccessNote("mirror.example.edu", allowed = false)), controller.turn.access)
    controller.say("again")
    runCurrent()
    assertTrue(controller.approvals.isEmpty())
    assertEquals(listOf(false, false, false, false), provider.answers.map { it.second })
  }

  @Test
  fun approve_deniedSiteAlwaysAllowedSince_opensItWithoutAsking() = runTest {
    for (allowing in listOf<(PageAccessSettings) -> PageAccessSettings>(
      { it.trusting("example.edu") },
      { it.copy(mode = PageAccessMode.Allow) },
    )) {
      val provider = FakeAiProvider(pages = listOf(page("mirror.example.edu")))
      val settings = settingsWith(provider)
      val controller = controller(settings)
      controller.say("blender")
      runCurrent()
      controller.answer(controller.approvals.single().id, PageAccessChoice.Deny)
      runCurrent()

      settings.saveAccess(allowing)
      applySnapshotChanges()
      controller.say("again")
      runCurrent()

      assertTrue(controller.approvals.isEmpty())
      assertEquals(listOf(false, true), provider.answers.map { it.second })
    }
  }

  @Test
  fun approve_allowAll_allowsEverySiteInTheSession() = runTest {
    val blender = page("download.blender.org")
    val github = page("github.com")
    val provider = FakeAiProvider(pages = listOf(blender, github))
    val controller = controller(provider)
    controller.say("blender")
    runCurrent()

    controller.answer(controller.approvals.single().id, PageAccessChoice.AllowAll)
    runCurrent()

    assertEquals(listOf(blender to true, github to true), provider.answers)
    assertEquals(listOf(AccessNote("download.blender.org", allowed = true)), controller.turn.access)
  }

  @Test
  fun approve_alwaysAllow_trustsTheSiteWithoutRebuildingTheProvider() = runTest {
    val provider = FakeAiProvider(pages = listOf(page("www.blender.org")))
    var built = 0
    val settings = AiSettingsController(factory = { built++; provider }).apply {
      save(AiSettings(enabled = true))
    }
    val controller = controller(settings)
    controller.say("blender")
    runCurrent()

    controller.answer(controller.approvals.single().id, PageAccessChoice.AlwaysAllow)
    runCurrent()

    assertEquals(listOf("blender.org"), settings.settings.access.trustedSites)
    assertEquals(1, built)
    assertSame(provider, settings.provider)
    assertEquals(listOf(true), provider.answers.map { it.second })
  }

  @Test
  fun approve_allowModeOrTrustedSite_neverAsks() = runTest {
    val provider = FakeAiProvider(pages = listOf(page("download.blender.org")))
    val allowing = controller(provider, PageAccessSettings(mode = PageAccessMode.Allow))
    val trusting = controller(
      provider,
      PageAccessSettings(mode = PageAccessMode.AskEveryTime, trustedSites = listOf("blender.org")),
    )

    allowing.say("blender")
    trusting.say("blender")
    runCurrent()

    assertTrue(allowing.approvals.isEmpty() && trusting.approvals.isEmpty())
    assertEquals(listOf(true, true), provider.answers.map { it.second })
  }

  @Test
  fun approve_turnLimitedToWebsites_neverAsks() = runTest {
    val provider = FakeAiProvider(pages = listOf(page("objects.githubusercontent.com")))
    val controller = controller(provider)

    controller.say("ffmpeg", sites = "github.com")
    runCurrent()

    assertTrue(controller.approvals.isEmpty())
    assertEquals(listOf(true), provider.answers.map { it.second })
    assertTrue(controller.turn.access.isEmpty())
  }

  @Test
  fun answer_alwaysAllow_answersOtherSessionsWaitingOnTheSite() = runTest {
    val provider = FakeAiProvider(pages = listOf(page("download.blender.org")))
    val controller = controller(provider)
    controller.say("blender")
    controller.newSession()
    controller.say("blender lts")
    runCurrent()
    assertEquals(2, controller.approvals.size)

    controller.answer(controller.approvals.first().id, PageAccessChoice.AlwaysAllow)
    runCurrent()

    assertTrue(controller.approvals.isEmpty())
    assertEquals(listOf(true, true), provider.answers.map { it.second })
    assertTrue(controller.turn.access.isEmpty(), "an answer the settings gave leaves no note")
  }

  @Test
  fun settingsChange_allowEverySite_answersWaitingRequests() = runTest {
    val provider = FakeAiProvider(pages = listOf(page("download.blender.org")))
    val settings = settingsWith(provider)
    val controller = controller(settings)
    controller.say("blender")
    runCurrent()
    assertEquals(1, controller.approvals.size)

    settings.saveAccess { it.copy(mode = PageAccessMode.Allow) }
    applySnapshotChanges()
    runCurrent()

    assertTrue(controller.approvals.isEmpty())
    assertEquals(listOf(true), provider.answers.map { it.second })
  }

  private companion object {
    const val NEXT_MODEL = "another-model"
  }
}

/** Keeps every message logged, with the throwable's text when there is one. */
private class RecordingLogger(private val records: MutableList<String>) : Logger {
  override fun v(message: String) {
    records += message
  }

  override fun d(message: String) {
    records += message
  }

  override fun i(message: String) {
    records += message
  }

  override fun w(message: String, throwable: Throwable?) {
    records += listOfNotNull(message, throwable?.toString()).joinToString(" ")
  }

  override fun e(message: String, throwable: Throwable?) {
    records += listOfNotNull(message, throwable?.toString()).joinToString(" ")
  }
}
