package com.linroid.ketch.cli

import com.linroid.ketch.ai.resolveAiSettingsFromEnv
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class CliLlmChoiceTest {

  private val saved = AiSettings()
    .withEntry(LlmSettings(id = "personal", provider = LlmProvider.OpenAi, apiKey = "p"))
    .withEntry(LlmSettings(id = "work", name = "Work", provider = LlmProvider.OpenAi, apiKey = "w"))
    .withEntry(LlmSettings(id = "local", provider = LlmProvider.Ollama))

  private fun chosen(settings: AiSettings, provider: String?): AiSettings =
    assertIs<CliLlmChoice.Chosen>(chooseLlm(settings, provider)).settings

  @Test
  fun chooseLlm_noProvider_keepsTheSettings() {
    assertEquals(saved, chosen(saved, null))
    // Untouched settings stay untouched, so the environment may still pick the provider.
    assertEquals(AiSettings(), chosen(AiSettings(), null))
  }

  @Test
  fun chooseLlm_savedProvider_matchesIdThenName() {
    assertEquals("work", chosen(saved, "work").llm.id)
    assertEquals("work", chosen(saved, "WORK").llm.id)
    assertEquals("local", chosen(saved, "ollama").llm.id)
  }

  @Test
  fun chooseLlm_knownProviderNotSaved_takesItsKeyFromTheEnvironment() {
    val settings = chosen(saved, "deepseek")
    assertEquals(LlmProvider.DeepSeek, settings.llm.provider)
    val resolved = resolveAiSettingsFromEnv(
      base = settings,
      getenv = { if (it == "DEEPSEEK_API_KEY") "ds" else null },
    )
    assertEquals("ds", resolved.llm.apiKey)
    assertTrue(resolved.isUsable)
    // The saved providers are left as they were.
    assertEquals("w", resolved.entry("work")?.apiKey)
  }

  @Test
  fun chooseLlm_unknownName_listsWhatCanBeNamed() {
    val choice = assertIs<CliLlmChoice.Unknown>(chooseLlm(saved, "acme"))
    assertTrue("personal (OpenAI)" in choice.message, choice.message)
    assertTrue("work (Work)" in choice.message, choice.message)
    assertTrue("deepseek" in choice.message, choice.message)
  }
}
