package com.linroid.ketch.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiSettingsTest {

  @Test
  fun `blank model and base url fall back to provider defaults`() {
    val llm = LlmSettings(provider = LlmProvider.Anthropic, apiKey = "k")
    assertEquals(LlmProvider.Anthropic.defaultModel, llm.effectiveModel)
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
  fun `local providers are complete without a key`() {
    assertTrue(LlmSettings(provider = LlmProvider.Ollama).isComplete)
    assertFalse(LlmSettings(provider = LlmProvider.LmStudio).isComplete)
    assertTrue(LlmSettings(provider = LlmProvider.LmStudio, model = "qwen3-8b").isComplete)
  }

  @Test
  fun `every preset but openai-compatible has an endpoint`() {
    for (provider in LlmProvider.entries - LlmProvider.OpenAiCompatible) {
      assertTrue(provider.defaultBaseUrl.startsWith("http"), "${provider.id} has no endpoint")
      assertEquals(provider, LlmProvider.byId(provider.id))
    }
    assertEquals(LlmProvider.entries.size, LlmProvider.entries.map { it.id }.distinct().size)
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
      encoded.contains("[[ai.providers]]") && encoded.contains("[ai.search]"),
      "expected nested ai sections, got:\n$encoded",
    )
    assertTrue(
      encoded.contains("provider = \"openai-compatible\"") &&
        encoded.contains("active = \"openai-compatible\""),
      "expected the provider id in TOML, got:\n$encoded",
    )
    assertFalse(encoded.contains("[ai.llm]"), "expected no legacy section, got:\n$encoded")
    val decoded = ConfigStore.decode(encoded)
    assertEquals(config.ai, decoded.ai)
  }

  @Test
  fun `several providers and their models round trip`() {
    var ai = AiSettings()
    val openAi = ai.newEntry(LlmProvider.OpenAi).copy(apiKey = "personal")
    ai = ai.withEntry(openAi)
    val work = ai.newEntry(LlmProvider.OpenAi).copy(apiKey = "work").withModel("gpt-custom")
    ai = ai.withEntry(work)
    ai = ai.withEntry(ai.newEntry(LlmProvider.Ollama)).withActive(work.id)
    ai = ai.copy(contentFilter = false)
    val encoded = ConfigStore.toml.encodeToString(KetchConfig.serializer(), KetchConfig(ai = ai))
    assertEquals(3, Regex("""\[\[ai\.providers]]""").findAll(encoded).count(), encoded)
    val decoded = ConfigStore.decode(encoded).ai
    assertEquals(ai, decoded)
    assertEquals("work", decoded.llm.apiKey)
    assertEquals("gpt-custom", decoded.llm.effectiveModel)
    assertFalse(decoded.contentFilter)
  }

  @Test
  fun `a config with one llm section loads as one active provider`() {
    val decoded = ConfigStore.decode(
      """
      |[ai]
      |enabled = true
      |
      |[ai.llm]
      |provider = "anthropic"
      |apiKey = "sk-ant"
      |model = "claude-sonnet-5"
      |baseUrl = ""
      """.trimMargin(),
    ).ai
    val entry = decoded.providers.single()
    assertEquals(LlmProvider.Anthropic, entry.provider)
    assertEquals("sk-ant", entry.apiKey)
    assertEquals("claude-sonnet-5", entry.model)
    assertEquals(entry, decoded.llm)
    assertTrue(decoded.isUsable)
    // Saved again, it is written the new way and reads back the same.
    val encoded = ConfigStore.toml
      .encodeToString(KetchConfig.serializer(), KetchConfig(ai = decoded))
    assertFalse(encoded.contains("[ai.llm]"), encoded)
    assertEquals(decoded, ConfigStore.decode(encoded).ai)
  }

  @Test
  fun `an untouched llm section loads as untouched settings`() {
    val decoded = ConfigStore.decode(
      """
      |[ai]
      |enabled = true
      |
      |[ai.llm]
      |provider = "openai"
      |apiKey = ""
      |model = ""
      |baseUrl = ""
      """.trimMargin(),
    ).ai
    assertEquals(AiSettings(), decoded)
  }

  @Test
  fun `an unknown provider id loads as openai-compatible`() {
    val decoded = ConfigStore.decode(
      """
      |[ai]
      |active = "future"
      |
      |[[ai.providers]]
      |id = "future"
      |provider = "a-provider-added-later"
      |apiKey = "k"
      |baseUrl = "https://llm.example.com/v1"
      |model = "m"
      """.trimMargin(),
    ).ai
    assertEquals(LlmProvider.OpenAiCompatible, decoded.llm.provider)
    assertEquals("https://llm.example.com/v1", decoded.llm.baseUrl)
    assertTrue(decoded.isUsable)
  }

  @Test
  fun `providers without unique ids get them when loaded`() {
    val decoded = ConfigStore.decode(
      """
      |[[ai.providers]]
      |provider = "deepseek"
      |apiKey = "a"
      |
      |[[ai.providers]]
      |id = "deepseek"
      |provider = "deepseek"
      |apiKey = "b"
      """.trimMargin(),
    ).ai
    assertEquals(listOf("deepseek", "deepseek-2"), decoded.providers.map { it.id })
    assertEquals("a", decoded.llm.apiKey)
  }

  @Test
  fun `without saved providers the default one is offered and active`() {
    val settings = AiSettings()
    assertEquals(listOf(LlmSettings()), settings.entries)
    assertEquals(LlmProvider.OpenAi, settings.llm.provider)
    // Editing it saves it, as the active one.
    val saved = settings.withEntry(settings.llm.copy(apiKey = "k"))
    assertEquals("openai", saved.providers.single().id)
    assertEquals("openai", saved.active)
    assertTrue(saved.isUsable)
  }

  @Test
  fun `new providers get unused ids and names`() {
    var settings = AiSettings(llm = LlmSettings(provider = LlmProvider.OpenAi, apiKey = "k"))
    val second = settings.newEntry(LlmProvider.OpenAi)
    assertEquals("openai-2", second.id)
    assertEquals("OpenAI 2", second.displayName)
    settings = settings.withEntry(second)
    // Adding another provider keeps the one in use.
    assertEquals("openai", settings.llm.id)
    assertEquals("openai-3", settings.newEntry(LlmProvider.OpenAi).id)
    assertEquals("", settings.newEntry(LlmProvider.Mistral).name)
  }

  @Test
  fun `removing the active provider makes the first one left active`() {
    val settings = AiSettings()
      .withEntry(LlmSettings(id = "a", provider = LlmProvider.DeepSeek, apiKey = "k"))
      .withEntry(LlmSettings(id = "b", provider = LlmProvider.Mistral, apiKey = "k"))
      .withActive("b")
    assertEquals("b", settings.llm.id)
    assertEquals("a", settings.withoutEntry("b").llm.id)
    assertEquals("b", settings.withoutEntry("a").llm.id)
    assertEquals(AiSettings(active = ""), settings.withoutEntry("a").withoutEntry("b"))
  }

  @Test
  fun `choosing a model adds it once and makes it the one called`() {
    val settings = AiSettings(llm = LlmSettings(provider = LlmProvider.OpenAi, apiKey = "k"))
    val suggestion = LlmProvider.OpenAi.models.last()
    val chosen = settings.withActive("openai", suggestion)
    // A suggestion is not added to the user's models.
    assertEquals(emptyList(), chosen.llm.models)
    assertEquals(suggestion, chosen.llm.effectiveModel)
    val custom = chosen.withActive("openai", "my-model").withActive("openai", "my-model")
    assertEquals(listOf("my-model"), custom.llm.models)
    assertEquals("my-model", custom.llm.modelChoices.first())
    val forgotten = custom.llm.withoutModel("my-model")
    assertEquals(LlmProvider.OpenAi.defaultModel, forgotten.effectiveModel)
    assertEquals(LlmProvider.OpenAi.models, forgotten.modelChoices)
  }

  @Test
  fun `engine settings leave out what the engine never reads`() {
    val settings = AiSettings()
      .withEntry(LlmSettings(id = "a", provider = LlmProvider.DeepSeek, apiKey = "k"))
      .withEntry(LlmSettings(id = "b", provider = LlmProvider.Mistral, apiKey = "k"))
    val renamed = settings.withEntry(settings.llm.copy(name = "Mine", models = listOf("x")))
    val otherChanged = settings.withEntry(settings.entry("b")!!.copy(apiKey = "other"))
    assertEquals(settings.engineSettings, renamed.engineSettings)
    assertEquals(settings.engineSettings, otherChanged.engineSettings)
    assertTrue(settings.engineSettings != settings.withActive("b").engineSettings)
    assertEquals(AiSettings(), AiSettings().engineSettings)
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
    val decoded = ConfigStore.decode(encoded)
    assertEquals(SearchProvider.Brave, decoded.ai.search.provider)
  }

  @Test
  fun `a retired bing search provider loads as none`() {
    val decoded = ConfigStore.decode(
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
    val decoded = ConfigStore.decode(
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
    val decoded = ConfigStore.decode(encoded)
    assertEquals(config.ai, decoded.ai)
  }

  @Test
  fun `an unknown page access mode loads as ask once per site`() {
    val decoded = ConfigStore.decode(
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
    val decoded = ConfigStore.decode(
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

  @Test
  fun `the content filter is on by default and left out of the engine settings`() {
    val decoded = ConfigStore.decode(
      """
      |[ai]
      |enabled = true
      """.trimMargin(),
    )
    assertTrue(decoded.ai.contentFilter)
    val off = AiSettings(enabled = true, contentFilter = false)
    assertEquals(AiSettings(enabled = true), off.engineSettings)
    val encoded = ConfigStore.toml.encodeToString(KetchConfig.serializer(), KetchConfig(ai = off))
    assertEquals(off, ConfigStore.decode(encoded).ai)
  }
}
