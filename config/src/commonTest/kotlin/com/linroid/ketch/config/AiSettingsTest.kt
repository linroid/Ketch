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
  fun `search provider is stored as its id`() {
    val config = KetchConfig(
      ai = AiSettings(search = SearchSettings(provider = SearchProvider.Brave, apiKey = "k")),
    )
    val encoded = ConfigStore.toml.encodeToString(KetchConfig.serializer(), config)
    assertTrue(
      encoded.contains("provider = \"brave\""),
      "expected the provider id in TOML, got:\n$encoded",
    )
    val decoded = ConfigStore.toml.decodeFromString(KetchConfig.serializer(), encoded)
    assertEquals(SearchProvider.Brave, decoded.ai.search.provider)
  }

  @Test
  fun `a retired bing search provider loads as none`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[ai]
      |enabled = true
      |
      |[ai.search]
      |provider = "bing"
      |apiKey = "old-key"
      """.trimMargin(),
    )
    assertEquals(SearchProvider.None, decoded.ai.search.provider)
    // The rest of the file still loads.
    assertTrue(decoded.ai.enabled)
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
  @Test
  fun `page access is stored under its own section`() {
    val config = KetchConfig(
      ai = AiSettings(
        enabled = true,
        access = PageAccessSettings(
          mode = PageAccessMode.AskEveryTime,
          trustedSites = listOf("ubuntu.com", "blender.org"),
        ),
      ),
    )
    val encoded = ConfigStore.toml.encodeToString(KetchConfig.serializer(), config)
    assertTrue(
      encoded.contains("[ai.access]") && encoded.contains("mode = \"ask\""),
      "expected the access section and mode id in TOML, got:\n$encoded",
    )
    val decoded = ConfigStore.toml.decodeFromString(KetchConfig.serializer(), encoded)
    assertEquals(config.ai, decoded.ai)
  }

  @Test
  fun `an unknown page access mode loads as ask once per site`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[ai]
      |enabled = true
      |
      |[ai.access]
      |mode = "ask-the-moon"
      |trustedSites = ["ubuntu.com"]
      """.trimMargin(),
    )
    assertEquals(PageAccessMode.AskPerSite, decoded.ai.access.mode)
    assertEquals(listOf("ubuntu.com"), decoded.ai.access.trustedSites)
  }

  @Test
  fun `an ai section without access asks once per site`() {
    val decoded = ConfigStore.toml.decodeFromString(
      KetchConfig.serializer(),
      """
      |[ai]
      |enabled = true
      """.trimMargin(),
    )
    assertEquals(PageAccessSettings(), decoded.ai.access)
    assertEquals(PageAccessMode.AskPerSite, decoded.ai.access.mode)
  }

  @Test
  fun `engine settings leave page access out`() {
    val settings = AiSettings(
      enabled = true,
      llm = LlmSettings(provider = LlmProvider.Ollama),
      access = PageAccessSettings(mode = PageAccessMode.Allow, trustedSites = listOf("a.org")),
    )
    assertEquals(settings.copy(access = PageAccessSettings()), settings.engineSettings)
    assertEquals(
      settings.engineSettings,
      settings.copy(access = PageAccessSettings(mode = PageAccessMode.AskEveryTime)).engineSettings,
    )
  }
}
