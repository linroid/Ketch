package com.linroid.ketch.ai

import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import com.linroid.ketch.config.SearchProvider
import com.linroid.ketch.config.SearchSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AiSettingsEnvTest {

  private fun env(vararg pairs: Pair<String, String>): (String) -> String? {
    val map = pairs.toMap()
    return { map[it] }
  }

  @Test
  fun `openai key alone configures and enables discovery`() {
    val settings = resolveAiSettingsFromEnv(
      getenv = env("OPENAI_API_KEY" to "sk-test"),
    )
    assertTrue(settings.enabled)
    assertEquals(LlmProvider.OpenAi, settings.llm.provider)
    assertEquals("sk-test", settings.llm.apiKey)
  }

  @Test
  fun `anthropic key alone selects anthropic`() {
    val settings = resolveAiSettingsFromEnv(
      getenv = env("ANTHROPIC_API_KEY" to "sk-ant"),
    )
    assertEquals(LlmProvider.Anthropic, settings.llm.provider)
    assertEquals("sk-ant", settings.llm.apiKey)
  }

  @Test
  fun `gemini key falls back to the google api key variable`() {
    val settings = resolveAiSettingsFromEnv(
      getenv = env("GOOGLE_API_KEY" to "goog"),
    )
    assertEquals(LlmProvider.Google, settings.llm.provider)
    assertEquals("goog", settings.llm.apiKey)
  }

  @Test
  fun `configured provider reads only its own variable`() {
    val base = AiSettings(
      enabled = true,
      llm = LlmSettings(provider = LlmProvider.Google),
    )
    val settings = resolveAiSettingsFromEnv(
      base = base,
      getenv = env(
        "OPENAI_API_KEY" to "sk-test",
        "GEMINI_API_KEY" to "gem",
      ),
    )
    assertEquals(LlmProvider.Google, settings.llm.provider)
    assertEquals("gem", settings.llm.apiKey)
  }

  @Test
  fun `a stored key is never overwritten by the environment`() {
    val base = AiSettings(
      enabled = true,
      llm = LlmSettings(provider = LlmProvider.OpenAi, apiKey = "stored"),
    )
    val settings = resolveAiSettingsFromEnv(
      base = base,
      getenv = env("OPENAI_API_KEY" to "from-env"),
    )
    assertEquals("stored", settings.llm.apiKey)
  }

  @Test
  fun `an environment key does not re-enable configured settings`() {
    val base = AiSettings(
      enabled = false,
      llm = LlmSettings(provider = LlmProvider.Anthropic),
    )
    val settings = resolveAiSettingsFromEnv(
      base = base,
      getenv = env("ANTHROPIC_API_KEY" to "sk-ant"),
    )
    assertFalse(settings.enabled)
    assertEquals("sk-ant", settings.llm.apiKey)
  }

  @Test
  fun `bing search credentials are picked up from the environment`() {
    val settings = resolveAiSettingsFromEnv(
      getenv = env("BING_SEARCH_API_KEY" to "bing-key"),
    )
    assertEquals(SearchProvider.Bing, settings.search.provider)
    assertEquals("bing-key", settings.search.apiKey)
  }

  @Test
  fun `google search needs both key and engine id`() {
    val keyOnly = resolveAiSettingsFromEnv(
      getenv = env("GOOGLE_SEARCH_API_KEY" to "key"),
    )
    assertEquals(SearchProvider.None, keyOnly.search.provider)
    val complete = resolveAiSettingsFromEnv(
      getenv = env(
        "GOOGLE_SEARCH_API_KEY" to "key",
        "GOOGLE_SEARCH_CX" to "cx",
      ),
    )
    assertEquals(SearchProvider.Google, complete.search.provider)
    assertEquals("cx", complete.search.cx)
  }

  @Test
  fun `configured search settings win over the environment`() {
    val base = AiSettings(
      search = SearchSettings(
        provider = SearchProvider.Bing, apiKey = "stored",
      ),
    )
    val settings = resolveAiSettingsFromEnv(
      base = base,
      getenv = env("GOOGLE_SEARCH_API_KEY" to "key", "GOOGLE_SEARCH_CX" to "cx"),
    )
    assertEquals(SearchProvider.Bing, settings.search.provider)
    assertEquals("stored", settings.search.apiKey)
  }

  @Test
  fun `a saved google key is kept and the engine id comes from the environment`() {
    // Review case: previously this stayed incomplete because both
    // values had to come from the environment.
    val base = AiSettings(
      enabled = true,
      search = SearchSettings(provider = SearchProvider.Google, apiKey = "saved"),
    )
    val settings = resolveAiSettingsFromEnv(
      base = base,
      getenv = env("GOOGLE_SEARCH_CX" to "cx-from-env"),
    )
    assertEquals(SearchProvider.Google, settings.search.provider)
    assertEquals("saved", settings.search.apiKey)
    assertEquals("cx-from-env", settings.search.cx)
    assertTrue(settings.search.isComplete)
  }

  @Test
  fun `a chosen search provider is never switched to another one`() {
    // Review case: a Bing key in the environment used to silently
    // replace an incomplete Google choice.
    val base = AiSettings(
      enabled = true,
      search = SearchSettings(provider = SearchProvider.Google),
    )
    val settings = resolveAiSettingsFromEnv(
      base = base,
      getenv = env("BING_SEARCH_API_KEY" to "bing-key"),
    )
    assertEquals(SearchProvider.Google, settings.search.provider)
    assertFalse(settings.search.isComplete)
  }

  @Test
  fun `a chosen bing provider gets its blank key from the environment`() {
    val base = AiSettings(
      enabled = true,
      search = SearchSettings(provider = SearchProvider.Bing),
    )
    val settings = resolveAiSettingsFromEnv(
      base = base,
      getenv = env("BING_SEARCH_API_KEY" to "bing-key"),
    )
    assertEquals("bing-key", settings.search.apiKey)
  }

  @Test
  fun `no search provider stays none once anything is configured`() {
    // "None" only yields to the environment for untouched settings.
    val base = AiSettings(
      enabled = true,
      llm = LlmSettings(provider = LlmProvider.OpenAi, apiKey = "sk"),
    )
    val settings = resolveAiSettingsFromEnv(
      base = base,
      getenv = env("BING_SEARCH_API_KEY" to "bing-key"),
    )
    assertEquals(SearchProvider.None, settings.search.provider)
  }
}
