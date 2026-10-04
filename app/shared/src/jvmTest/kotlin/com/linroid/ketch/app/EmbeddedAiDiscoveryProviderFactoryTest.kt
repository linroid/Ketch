package com.linroid.ketch.app

import com.linroid.ketch.ai.DiscoverQuery
import com.linroid.ketch.ai.DiscoverResult
import com.linroid.ketch.ai.DiscoverTurn
import com.linroid.ketch.ai.PageAccessApprover
import com.linroid.ketch.ai.PageAccessKind
import com.linroid.ketch.ai.PageAccessRequest
import com.linroid.ketch.ai.agent.DiscoveryStepListener
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.AiDiscoverRequest
import com.linroid.ketch.app.state.AiDiscoverTurn
import com.linroid.ketch.app.state.AiPageKind
import com.linroid.ketch.app.state.AiPageRequest
import com.linroid.ketch.app.state.DiscoveryStep
import com.linroid.ketch.app.state.EmbeddedAiDiscoveryProvider
import com.linroid.ketch.app.state.EmbeddedAiDiscoveryProviderFactory
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Wiring check for the desktop/Android factory: settings in, a live
 * discovery engine out, and how a provider hands each search to the
 * engine. No network calls are made.
 */
class EmbeddedAiDiscoveryProviderFactoryTest {

  /** Hermetic: no credentials leak in from the developer's shell. */
  private val factory = EmbeddedAiDiscoveryProviderFactory { null }

  @Test
  fun `disabled settings produce no provider`() {
    val settings = AiSettings(
      enabled = false,
      llm = LlmSettings(provider = LlmProvider.OpenAi, apiKey = "sk-test"),
    )
    assertNull(factory.create(settings))
  }

  @Test
  fun `missing credentials produce no provider`() {
    assertNull(factory.create(AiSettings(enabled = true)))
  }

  @Test
  fun `complete settings build a closable provider`() {
    val settings = AiSettings(
      enabled = true,
      llm = LlmSettings(provider = LlmProvider.OpenAi, apiKey = "sk-test"),
    )
    val provider = factory.create(settings)
    assertNotNull(provider)
    // Closing releases the engine's HTTP clients; it must not throw.
    provider.close()
  }

  private val envFactory = EmbeddedAiDiscoveryProviderFactory { name ->
    "sk-from-env".takeIf { name == "OPENAI_API_KEY" }
  }

  @Test
  fun `a blank token is filled from the environment`() {
    val provider = envFactory.create(AiSettings())
    assertNotNull(provider, "an environment key should supply the token")
    provider.close()
  }

  @Test
  fun `an environment key does not switch discovery back on`() {
    assertNull(envFactory.create(AiSettings(enabled = false)))
  }

  @Test
  fun `platform credentials fill the selected provider's blank token`() {
    val resolved = envFactory.withPlatformCredentials(AiSettings(enabled = false))
    assertEquals("sk-from-env", resolved.llm.apiKey)
    assertFalse(resolved.enabled, "resolving must not switch the feature on")
  }

  @Test
  fun `platform credentials never pick a different provider`() {
    // Only an Anthropic key exists; the form still says OpenAI.
    val anthropicOnly = EmbeddedAiDiscoveryProviderFactory { name ->
      "sk-ant".takeIf { name == "ANTHROPIC_API_KEY" }
    }
    val resolved = anthropicOnly.withPlatformCredentials(AiSettings())
    assertEquals(LlmProvider.OpenAi, resolved.llm.provider)
    assertEquals("", resolved.llm.apiKey)
  }

  @Test
  fun `ollama needs no token`() {
    val settings = AiSettings(
      enabled = true,
      llm = LlmSettings(provider = LlmProvider.Ollama),
    )
    val provider = factory.create(settings)
    assertNotNull(provider)
    provider.close()
  }

  /** A provider over an engine that runs [search], counting how often it is [released]. */
  private class Engine(
    val search: suspend (DiscoverQuery, DiscoveryStepListener, PageAccessApprover) ->
      DiscoverResult = { query, _, _ -> result(query) },
  ) {
    var released = 0
    val provider = EmbeddedAiDiscoveryProvider(search, verifyConnection = { "OK" }) { released++ }
  }

  @Test
  fun `searches running at once each get their own trimmed steps`() = runTest {
    val gates = mapOf("a" to CompletableDeferred<Unit>(), "b" to CompletableDeferred())
    val engine = Engine { query, steps, _ ->
      gates.getValue(query.query).await()
      steps.onStep(" Plan ", "Search for ${query.query}\n")
      result(query)
    }
    val stepsOfA = mutableListOf<DiscoveryStep>()
    val stepsOfB = mutableListOf<DiscoveryStep>()
    val a = async { engine.provider.discover(AiDiscoverRequest("a"), { stepsOfA += it }, { true }) }
    val b = async { engine.provider.discover(AiDiscoverRequest("b"), { stepsOfB += it }, { true }) }
    runCurrent()

    gates.getValue("b").complete(Unit)
    gates.getValue("a").complete(Unit)
    a.await()
    b.await()

    assertEquals(listOf(DiscoveryStep("Plan", "Search for a")), stepsOfA)
    assertEquals(listOf(DiscoveryStep("Plan", "Search for b")), stepsOfB)
  }

  @Test
  fun `the engine's page requests reach the search's approver with its answer`() = runTest {
    val engine = Engine { query, _, approver ->
      val request = PageAccessRequest(
        url = "https://objects.githubusercontent.com/f",
        host = "objects.githubusercontent.com",
        kind = PageAccessKind.FileInfo,
        reason = "Check the size",
        redirectFrom = "github.com",
      )
      result(query, summary = if (approver.approve(request)) "allowed" else "declined")
    }
    val asked = mutableListOf<AiPageRequest>()

    val response = engine.provider.discover(AiDiscoverRequest("ffmpeg"), {}, { asked += it; false })

    val expected = AiPageRequest(
      url = "https://objects.githubusercontent.com/f",
      host = "objects.githubusercontent.com",
      kind = AiPageKind.FileInfo,
      reason = "Check the size",
      redirectFrom = "github.com",
    )
    assertEquals(listOf(expected), asked)
    assertEquals("declined", response.summary)
  }

  @Test
  fun `the engine's title and summary reach the response`() = runTest {
    val engine = Engine { query, _, _ ->
      result(query, summary = "One build.", title = "Blender 4.2 for Apple silicon")
    }

    val response = engine.provider.discover(AiDiscoverRequest("blender"), {}, { true })

    assertEquals("Blender 4.2 for Apple silicon", response.title)
    assertEquals("One build.", response.summary)
  }

  @Test
  fun `a follow-up reaches the engine with its history details and discarded links`() = runTest {
    var seen: DiscoverQuery? = null
    val engine = Engine { query, _, _ -> result(query).also { seen = query } }
    val earlier = AiCandidate(
      url = "https://download.blender.org/a.dmg",
      title = "Blender 4.2",
      fileName = "a.dmg",
      fileSize = 2048L,
      mimeType = "application/x-apple-diskimage",
      sourceUrl = "https://www.blender.org/download/",
      confidence = 0.9f,
      description = "The installer",
    )

    engine.provider.discover(
      AiDiscoverRequest(
        query = "only arm64",
        sites = listOf("blender.org"),
        history = listOf(AiDiscoverTurn("blender", emptyList(), true, listOf(earlier))),
        excludedUrls = setOf("https://download.blender.org/b.dmg"),
      ),
      onStep = {},
      approve = { true },
    )

    val turn = DiscoverTurn(
      request = "blender",
      sites = emptyList(),
      completed = true,
      results = listOf(
        DiscoverTurn.Result(
          url = earlier.url,
          title = earlier.title,
          fileName = "a.dmg",
          sizeBytes = 2048L,
          mimeType = "application/x-apple-diskimage",
          sourceUrl = "https://www.blender.org/download/",
          description = "The installer",
          confidence = 0.9f,
        ),
      ),
    )
    assertEquals(
      DiscoverQuery(
        query = "only arm64",
        sites = listOf("blender.org"),
        history = listOf(turn),
        excludedUrls = setOf("https://download.blender.org/b.dmg"),
      ),
      seen,
    )
  }

  @Test
  fun `closing during a search releases the engine once the search ends`() = runTest {
    val gate = CompletableDeferred<Unit>()
    val engine = Engine { query, _, _ ->
      gate.await()
      result(query)
    }
    val search = async { engine.provider.discover(AiDiscoverRequest("a"), {}, { true }) }
    runCurrent()

    engine.provider.close()
    assertEquals(0, engine.released, "a search running on the engine should go on")
    gate.complete(Unit)
    search.await()

    assertEquals(1, engine.released)
    engine.provider.close()
    assertEquals(1, engine.released)
  }

  @Test
  fun `closing an idle provider releases the engine at once`() {
    val engine = Engine()

    engine.provider.close()

    assertEquals(1, engine.released)
  }

  @Test
  fun `a search after closing never reaches the released engine`() = runTest {
    var searched = false
    val engine = Engine { query, _, _ ->
      searched = true
      result(query)
    }
    engine.provider.close()

    assertFailsWith<IllegalStateException> {
      engine.provider.discover(AiDiscoverRequest("a"), {}, { true })
    }

    assertFalse(searched)
    assertEquals(1, engine.released)
  }
}

private fun result(query: DiscoverQuery, summary: String = "", title: String = "") =
  DiscoverResult(
    query = query.query,
    candidates = emptyList(),
    sources = emptyList(),
    summary = summary,
    title = title,
  )
