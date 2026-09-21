package com.linroid.ketch.ai

import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLMProvider
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LlmClientFactoryTest {

  @Test
  fun `resolve returns null when the api key is missing`() {
    val resolved = LlmClientFactory.resolve(
      LlmSettings(provider = LlmProvider.OpenAi),
    )
    assertNull(resolved)
  }

  @Test
  fun `resolve returns null when a compatible endpoint is missing`() {
    val resolved = LlmClientFactory.resolve(
      LlmSettings(
        provider = LlmProvider.OpenAiCompatible,
        apiKey = "key",
        model = "llama-3.3-70b",
      ),
    )
    assertNull(resolved)
  }

  @Test
  fun `ollama resolves without an api key`() {
    val resolved = LlmClientFactory.resolve(
      LlmSettings(provider = LlmProvider.Ollama, model = "qwen2.5:7b"),
    )
    assertNotNull(resolved)
    assertEquals("qwen2.5:7b", resolved.model.id)
    assertEquals(LLMProvider.Ollama, resolved.model.provider)
  }

  @Test
  fun `known model id resolves to the koog catalog entry`() {
    val resolved = LlmClientFactory.resolve(
      LlmSettings(
        provider = LlmProvider.OpenAi,
        apiKey = "key",
        model = "gpt-4o",
      ),
    )
    assertNotNull(resolved)
    assertEquals("gpt-4o", resolved.model.id)
    // Catalog entries carry limits; hand-built models do not.
    assertNotNull(resolved.model.contextLength)
  }

  @Test
  fun `the current openai default resolves even though koog lacks it`() {
    // Models released after Koog's catalog must still work.
    val resolved = LlmClientFactory.resolve(
      LlmSettings(provider = LlmProvider.OpenAi, apiKey = "key"),
    )
    assertNotNull(resolved)
    assertEquals("gpt-5.6-terra", resolved.model.id)
    assertTrue(resolved.model.supports(LLMCapability.Tools))
  }

  @Test
  fun `unknown model id resolves to a custom tool-capable model`() {
    val resolved = LlmClientFactory.resolve(
      LlmSettings(
        provider = LlmProvider.OpenAi,
        apiKey = "key",
        model = "gpt-nonexistent-42",
      ),
    )
    assertNotNull(resolved)
    assertEquals("gpt-nonexistent-42", resolved.model.id)
    assertNull(resolved.model.contextLength)
    assertTrue(resolved.model.supports(LLMCapability.Tools))
  }

  @Test
  fun `anthropic custom model keeps the provider and id`() {
    val resolved = LlmClientFactory.resolve(
      LlmSettings(
        provider = LlmProvider.Anthropic,
        apiKey = "key",
        model = "claude-sonnet-4-5-20250929",
      ),
    )
    assertNotNull(resolved)
    assertEquals("claude-sonnet-4-5-20250929", resolved.model.id)
    assertEquals(LLMProvider.Anthropic, resolved.model.provider)
  }

  @Test
  fun `google uses the gemini default model`() {
    val resolved = LlmClientFactory.resolve(
      LlmSettings(provider = LlmProvider.Google, apiKey = "key"),
    )
    assertNotNull(resolved)
    assertEquals("gemini-3.8-flash", resolved.model.id)
    assertEquals(LLMProvider.Google, resolved.model.provider)
  }

  @Test
  fun `anthropic default is the current flagship`() {
    val resolved = LlmClientFactory.resolve(
      LlmSettings(provider = LlmProvider.Anthropic, apiKey = "key"),
    )
    assertNotNull(resolved)
    assertEquals("claude-opus-5", resolved.model.id)
  }

  @Test
  fun `openai custom models target the responses endpoint`() {
    // OpenAI's current models are served by the Responses API.
    val resolved = LlmClientFactory.resolve(
      LlmSettings(
        provider = LlmProvider.OpenAi,
        apiKey = "key",
        model = "gpt-6-astra",
      ),
    )
    assertNotNull(resolved)
    assertTrue(
      resolved.model.supports(LLMCapability.OpenAIEndpoint.Responses),
    )
    assertFalse(
      resolved.model.supports(LLMCapability.OpenAIEndpoint.Completions),
    )
  }

  @Test
  fun `compatible endpoints target chat completions`() {
    // Third-party servers implement /v1/chat/completions, not Responses.
    val resolved = LlmClientFactory.resolve(
      LlmSettings(
        provider = LlmProvider.OpenAiCompatible,
        apiKey = "key",
        model = "llama-3.3-70b",
        baseUrl = "https://openrouter.ai/api/v1",
      ),
    )
    assertNotNull(resolved)
    assertTrue(
      resolved.model.supports(LLMCapability.OpenAIEndpoint.Completions),
      "the OpenAI client rejects models without an endpoint capability",
    )
  }

  @Test
  fun `custom models do not claim sampling support`() {
    // Claude 5 and OpenAI's newest models 400 on temperature, and the
    // discovery service keys the parameter off this capability.
    LlmProvider.entries.forEach { provider ->
      val resolved = LlmClientFactory.resolve(
        LlmSettings(
          provider = provider,
          apiKey = "key",
          model = "some-unreleased-model",
          baseUrl = provider.defaultBaseUrl.ifBlank { "https://host/api" },
        ),
      )
      assertNotNull(resolved, "$provider should resolve a custom model")
      assertFalse(
        resolved.model.supports(LLMCapability.Temperature),
        "$provider custom models must not advertise temperature",
      )
    }
  }

  @Test
  fun `base url normalization drops a trailing version segment`() {
    assertEquals(
      "https://openrouter.ai/api",
      LlmClientFactory.normalizeOpenAiBaseUrl("https://openrouter.ai/api/v1"),
    )
    assertEquals(
      "http://localhost:1234",
      LlmClientFactory.normalizeOpenAiBaseUrl("http://localhost:1234/v1/"),
    )
    assertEquals(
      "https://api.deepseek.com",
      LlmClientFactory.normalizeOpenAiBaseUrl(" https://api.deepseek.com "),
    )
  }

  @Test
  fun `base url normalization keeps a bare version host`() {
    assertEquals("/v1", LlmClientFactory.normalizeOpenAiBaseUrl("/v1"))
  }
}
