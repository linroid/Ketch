package com.linroid.ketch.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiSettingsTest {

  @Test
  fun `blank model and base url fall back to provider defaults`() {
    val llm = LlmSettings(provider = LlmProvider.Anthropic, apiKey = "k")
    assertEquals("claude-opus-5", llm.effectiveModel)
    assertEquals("https://api.anthropic.com", llm.effectiveBaseUrl)
  }

  @Test
  fun `explicit model and base url win over defaults`() {
    val llm = LlmSettings(
      provider = LlmProvider.OpenAi,
      apiKey = "k",
      model = "gpt-5.6-sol",
      baseUrl = "https://proxy.internal",
    )
    assertEquals("gpt-5.6-sol", llm.effectiveModel)
    assertEquals("https://proxy.internal", llm.effectiveBaseUrl)
  }

  @Test
  fun `provider requiring a key is incomplete without one`() {
    assertFalse(LlmSettings(provider = LlmProvider.OpenAi).isComplete)
    assertTrue(
      LlmSettings(provider = LlmProvider.OpenAi, apiKey = "k").isComplete,
    )
  }

  @Test
  fun `ollama is complete without a key`() {
    assertTrue(LlmSettings(provider = LlmProvider.Ollama).isComplete)
  }

  @Test
  fun `openai-compatible needs an explicit base url and model`() {
    val noEndpoint = LlmSettings(
      provider = LlmProvider.OpenAiCompatible,
      apiKey = "k",
      model = "llama-3.3-70b",
    )
    assertFalse(noEndpoint.isComplete)
    val noModel = LlmSettings(
      provider = LlmProvider.OpenAiCompatible,
      apiKey = "k",
      baseUrl = "https://openrouter.ai/api",
    )
    assertFalse(noModel.isComplete)
    assertTrue(noModel.copy(model = "llama-3.3-70b").isComplete)
  }

  @Test
  fun `google search is incomplete without an engine id`() {
    val search = SearchSettings(
      provider = SearchProvider.Google, apiKey = "k",
    )
    assertFalse(search.isComplete)
    assertTrue(search.copy(cx = "cx-id").isComplete)
  }

  @Test
  fun `no search provider needs no credentials`() {
    assertTrue(SearchSettings().isComplete)
  }

  @Test
  fun `settings are unusable while disabled`() {
    val settings = AiSettings(
      enabled = false,
      llm = LlmSettings(provider = LlmProvider.OpenAi, apiKey = "k"),
    )
    assertFalse(settings.isUsable)
    assertTrue(settings.copy(enabled = true).isUsable)
  }

  @Test
  fun `toml round trip keeps provider ids and nested sections`() {
    val config = KetchConfig(
      ai = AiSettings(
        enabled = true,
        llm = LlmSettings(
          provider = LlmProvider.OpenAiCompatible,
          apiKey = "llm-key",
          model = "llama-3.3-70b",
          baseUrl = "https://openrouter.ai/api",
        ),
        search = SearchSettings(
          provider = SearchProvider.Google,
          apiKey = "search-key",
          cx = "cx-id",
        ),
      ),
    )
    val encoded = ConfigStore.toml
      .encodeToString(KetchConfig.serializer(), config)
    // The section names and provider ids are a hand-editable file
    // format, so they are part of the contract.
    assertTrue(
      encoded.contains("[ai.llm]") && encoded.contains("[ai.search]"),
      "expected nested ai sections, got:\n$encoded",
    )
    assertTrue(
      encoded.contains("provider = \"openai-compatible\""),
      "expected the serial name in TOML, got:\n$encoded",
    )
    val decoded = ConfigStore.toml
      .decodeFromString(KetchConfig.serializer(), encoded)
    assertEquals(config.ai, decoded.ai)
  }

  @Test
  fun `config without an ai section decodes to defaults`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |name = "laptop"
      |
      |[server]
      |port = 8642
      """.trimMargin(),
    )
    assertEquals(AiSettings(), decoded.ai)
  }
}
