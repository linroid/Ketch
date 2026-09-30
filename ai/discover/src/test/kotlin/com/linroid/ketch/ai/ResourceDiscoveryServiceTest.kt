package com.linroid.ketch.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.linroid.ketch.ai.agent.DiscoveryStepListener
import com.linroid.ketch.ai.fetch.ContentExtractor
import com.linroid.ketch.ai.fetch.SafeFetcher
import com.linroid.ketch.ai.fetch.UrlValidator
import com.linroid.ketch.ai.fetch.fakeDns
import com.linroid.ketch.ai.search.DummySearchProvider
import com.linroid.ketch.ai.site.SiteProfiler
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ResourceDiscoveryServiceTest {

  /** Answers every prompt with [reply], or fails when it is `null`. */
  private class FakeExecutor(private val reply: String?) : PromptExecutor() {
    var closeCount = 0
      private set

    override suspend fun execute(
      prompt: Prompt,
      model: LLModel,
      tools: List<ToolDescriptor>,
    ): Message.Assistant {
      check(closeCount == 0) { "Used after close" }
      return Message.Assistant(reply ?: error("LLM unavailable"), ResponseMetaInfo.Empty)
    }

    override fun executeStreaming(
      prompt: Prompt,
      model: LLModel,
      tools: List<ToolDescriptor>,
    ): Flow<StreamFrame> = error("Not used by discovery")

    override suspend fun moderate(prompt: Prompt, model: LLModel): ModerationResult =
      error("Not used by discovery")

    override fun close() {
      closeCount++
    }
  }

  private val ollama = LlmSettings(provider = LlmProvider.Ollama, model = "fake")

  private fun service(
    executors: MutableList<FakeExecutor>,
    reply: String? = "[]",
    enabled: Boolean = true,
  ): ResourceDiscoveryService {
    val validator = UrlValidator(resolve = fakeDns("example.com" to "93.184.215.14"))
    val fetcher = SafeFetcher(
      httpClient = HttpClient(MockEngine { respond("") }) { followRedirects = false },
      urlValidator = validator,
    )
    return ResourceDiscoveryService(
      searchProvider = DummySearchProvider(),
      fetcher = fetcher,
      urlValidator = validator,
      contentExtractor = ContentExtractor(),
      siteProfiler = SiteProfiler(fetcher),
      config = AiConfig(settings = AiSettings(enabled = enabled, llm = ollama)),
      stepListener = DiscoveryStepListener.None,
      resolveLlm = { settings ->
        val executor = FakeExecutor(reply).also(executors::add)
        ResolvedLlm(executor, LlmClientFactory.customModel(LLMProvider.Ollama, settings.model))
      },
    )
  }

  @Test
  fun discover_agentFinishes_closesLlmClient() = runTest {
    val executors = mutableListOf<FakeExecutor>()

    val reply = """[{"name": "Tool", "url": "https://example.com/tool.zip", "confidence": 0.9}]"""

    val result = service(executors, reply).discover(DiscoverQuery(query = "tool release"))

    assertEquals(listOf("https://example.com/tool.zip"), result.candidates.map { it.url })
    assertEquals(listOf(1), executors.map { it.closeCount })
  }

  @Test
  fun discover_agentFails_closesLlmClient() = runTest {
    val executors = mutableListOf<FakeExecutor>()

    val result = service(executors, reply = null).discover(DiscoverQuery(query = "ubuntu iso"))

    assertTrue(result.candidates.isEmpty())
    assertEquals(listOf(1), executors.map { it.closeCount })
  }

  @Test
  fun discover_everyRun_closesItsOwnLlmClient() = runTest {
    val executors = mutableListOf<FakeExecutor>()
    val service = service(executors)

    service.discover(DiscoverQuery(query = "ubuntu iso"))
    service.discover(DiscoverQuery(query = "ffmpeg release"))

    assertEquals(listOf(1, 1), executors.map { it.closeCount })
  }

  @Test
  fun discover_disabled_buildsNoLlmClient() = runTest {
    val executors = mutableListOf<FakeExecutor>()

    val result = service(executors, enabled = false).discover(DiscoverQuery(query = "ubuntu iso"))

    assertTrue(result.candidates.isEmpty())
    assertTrue(executors.isEmpty())
  }

  @Test
  fun verifyConnection_reply_closesLlmClient() = runTest {
    val executors = mutableListOf<FakeExecutor>()

    val reply = service(executors, reply = " OK\n").verifyConnection()

    assertEquals("OK", reply)
    assertEquals(listOf(1), executors.map { it.closeCount })
  }

  @Test
  fun verifyConnection_failure_closesLlmClient() = runTest {
    val executors = mutableListOf<FakeExecutor>()

    assertFailsWith<IllegalStateException> {
      service(executors, reply = null).verifyConnection()
    }

    assertEquals(listOf(1), executors.map { it.closeCount })
  }
}
