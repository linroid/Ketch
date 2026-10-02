package com.linroid.ketch.ai

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.Prompt
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.executor.model.PromptExecutor
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.MessagePart
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

  /** Answers every prompt with what [respond] returns, or fails when it returns `null`. */
  private class FakeExecutor(
    private val respond: (Prompt) -> Message.Assistant?,
  ) : PromptExecutor() {
    var closeCount = 0
      private set

    override suspend fun execute(
      prompt: Prompt,
      model: LLModel,
      tools: List<ToolDescriptor>,
    ): Message.Assistant {
      check(closeCount == 0) { "Used after close" }
      return respond(prompt) ?: error("LLM unavailable")
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
  ): ResourceDiscoveryService = service(executors, enabled) { _ ->
    reply?.let { Message.Assistant(it, ResponseMetaInfo.Empty) }
  }

  private fun service(
    executors: MutableList<FakeExecutor>,
    enabled: Boolean = true,
    agent: AgentConfig = AgentConfig(),
    steps: DiscoveryStepListener = DiscoveryStepListener.None,
    respond: (Prompt) -> Message.Assistant?,
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
      config = AiConfig(settings = AiSettings(enabled = enabled, llm = ollama), agent = agent),
      stepListener = steps,
      resolveLlm = { settings ->
        val executor = FakeExecutor(respond).also(executors::add)
        ResolvedLlm(executor, LlmClientFactory.customModel(LLMProvider.Ollama, settings.model))
      },
    )
  }

  private fun recordSteps(steps: MutableList<String>) = object : DiscoveryStepListener {
    override fun onStep(title: String, details: String) {
      steps += details
    }
  }

  /**
   * Calls `emitStep` until [spentReplies] tool results in [prompt] reported the tool budget
   * spent, then returns `null`.
   */
  private fun stepUntilSpent(prompt: Prompt, spentReplies: Int): Message.Assistant? {
    val results = prompt.messages.flatMap { it.parts }.filterIsInstance<MessagePart.Tool.Result>()
    if (results.count { it.output != "ok" } >= spentReplies) return null
    val call = MessagePart.Tool.Call(
      id = "call-${results.size}",
      tool = "emitStep",
      args = """{"title": "Searching", "details": "step ${results.size}"}""",
    )
    return Message.Assistant(call, ResponseMetaInfo.Empty)
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
  fun discover_agentAnswersAfterToolBudgetIsSpent_returnsItsCandidates() = runTest {
    val executors = mutableListOf<FakeExecutor>()
    val steps = mutableListOf<String>()
    val reply = """[{"name": "Tool", "url": "https://example.com/tool.zip", "confidence": 0.9}]"""
    // Reports steps until the budget is spent, reports one more as told, then answers
    val service = service(executors, steps = recordSteps(steps)) { prompt ->
      stepUntilSpent(prompt, spentReplies = 2) ?: Message.Assistant(reply, ResponseMetaInfo.Empty)
    }

    val result = service.discover(DiscoverQuery(query = "tool release"))

    assertEquals(listOf("https://example.com/tool.zip"), result.candidates.map { it.url })
    assertEquals(AgentConfig().maxToolCalls + 2, steps.size)
  }

  @Test
  fun discover_agentIgnoresSpentToolBudget_failsOutOfSteps() = runTest {
    val executors = mutableListOf<FakeExecutor>()
    val service = service(executors, agent = AgentConfig(maxToolCalls = 2)) { prompt ->
      stepUntilSpent(prompt, spentReplies = Int.MAX_VALUE)
    }

    val error = assertFailsWith<DiscoveryException> {
      service.discover(DiscoverQuery(query = "tool release"))
    }

    assertTrue(error.message.startsWith("The agent ran out of steps"))
    assertEquals(listOf(1), executors.map { it.closeCount })
  }

  @Test
  fun discover_agentFails_throwsAndClosesLlmClient() = runTest {
    val executors = mutableListOf<FakeExecutor>()

    val error = assertFailsWith<DiscoveryException> {
      service(executors, reply = null).discover(DiscoverQuery(query = "ubuntu iso"))
    }

    assertEquals("The AI request failed: LLM unavailable", error.message)
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

    assertFailsWith<DiscoveryException> {
      service(executors, reply = null).verifyConnection()
    }

    assertEquals(listOf(1), executors.map { it.closeCount })
  }
}
