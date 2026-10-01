package com.linroid.ketch.app.state

import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AiDiscoverControllerTest {

  private fun candidate(name: String) = AiCandidate(
    url = "https://example.com/$name",
    title = name,
    sourceUrl = "https://example.com/downloads",
    confidence = 0.9f,
    description = "",
  )

  /** Answers every search with [candidates], reporting [steps] first; waits for [gate]. */
  private class FakeProvider(
    private val steps: List<DiscoveryStep> = emptyList(),
    private val candidates: List<AiCandidate> = emptyList(),
    private val gate: CompletableDeferred<Unit>? = null,
  ) : AiDiscoveryProvider {
    val requests = mutableListOf<AiDiscoverRequest>()

    override suspend fun discover(
      request: AiDiscoverRequest,
      onStep: (DiscoveryStep) -> Unit,
    ): AiDiscoverResponse {
      requests += request
      steps.forEach(onStep)
      gate?.await()
      return AiDiscoverResponse(request.query, candidates)
    }

    override suspend fun verify(): String = "ok"
  }

  private fun settingsWith(provider: AiDiscoveryProvider): AiSettingsController =
    AiSettingsController(factory = { provider }).apply { save(AiSettings(enabled = true)) }

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
  fun discover_request_fillsTheDraftAndSearches() = runTest {
    val provider = FakeProvider(candidates = listOf(candidate("blender.dmg")))
    val controller = AiDiscoverController(settingsWith(provider), backgroundScope)

    controller.discover(DiscoverRequest("Blender for Apple silicon", listOf("blender.org")))
    runCurrent()

    assertEquals("Blender for Apple silicon", controller.draft.submittedQuery)
    assertEquals("blender.org", controller.draft.sites)
    assertEquals(listOf("blender.org"), provider.requests.single().sites)
    assertIs<AiDiscoverState.Results>(controller.state)
  }

  @Test
  fun discover_providerReportsSteps_followsThemInOrder() = runTest {
    val gate = CompletableDeferred<Unit>()
    val steps = listOf(DiscoveryStep("Understanding"), DiscoveryStep("Plan", "Search twice"))
    val controller = AiDiscoverController(
      settingsWith(FakeProvider(steps = steps, gate = gate)),
      backgroundScope,
    )

    controller.discover("blender", sites = "")
    runCurrent()

    assertEquals(AiDiscoverState.Loading, controller.state)
    assertEquals(steps, controller.steps)
    gate.complete(Unit)
    runCurrent()
    assertIs<AiDiscoverState.Results>(controller.state)
    assertEquals(steps, controller.steps)
  }

  @Test
  fun discover_notSetUp_waitsUntilSetUpThenRuns() = runTest {
    val provider = FakeProvider(candidates = listOf(candidate("ubuntu.iso")))
    val settings = AiSettingsController(factory = { if (it.enabled) provider else null })
    val controller = AiDiscoverController(settings, backgroundScope)

    controller.discover(DiscoverRequest("ubuntu server iso"))
    runCurrent()

    assertEquals(AiDiscoverState.Idle, controller.state)
    assertEquals(DiscoverRequest("ubuntu server iso"), controller.pending)
    settings.save(AiSettings(enabled = true, llm = LlmSettings(provider = LlmProvider.Ollama)))
    assertTrue(controller.runPending())
    runCurrent()
    assertNull(controller.pending)
    assertEquals("ubuntu server iso", provider.requests.single().query)
    assertIs<AiDiscoverState.Results>(controller.state)
  }

  @Test
  fun discover_unsupported_reportsAnError() = runTest {
    val controller = AiDiscoverController(AiSettingsController(), backgroundScope)

    controller.discover("blender", sites = "")

    assertIs<AiDiscoverState.Error>(controller.state)
    assertNull(controller.pending)
  }

  @Test
  fun stop_runningSearch_returnsToIdleAndKeepsTheSteps() = runTest {
    val steps = listOf(DiscoveryStep("Searching"))
    val controller = AiDiscoverController(
      settingsWith(FakeProvider(steps = steps, gate = CompletableDeferred())),
      backgroundScope,
    )
    controller.discover("blender", sites = "")
    runCurrent()

    controller.stop()
    runCurrent()

    assertEquals(AiDiscoverState.Idle, controller.state)
    assertEquals(steps, controller.steps)
  }

  @Test
  fun search_blankQuery_doesNothing() = runTest {
    val provider = FakeProvider()
    val controller = AiDiscoverController(settingsWith(provider), backgroundScope)
    controller.draft.query = "   "

    controller.search()
    runCurrent()

    assertTrue(provider.requests.isEmpty())
    assertEquals(AiDiscoverState.Idle, controller.state)
  }
}
