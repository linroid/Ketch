package com.linroid.ketch.app

import com.linroid.ketch.app.state.EmbeddedAiDiscoveryProviderFactory
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Wiring check for the desktop/Android factory: settings in, a live
 * discovery engine out. No network calls are made.
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
  fun `a blank token is filled from the environment once enabled`() {
    val provider = envFactory.create(AiSettings(enabled = true))
    assertNotNull(provider, "an environment key should supply the token")
    provider.close()
  }

  @Test
  fun `an environment key does not switch discovery on`() {
    // The apps show an Enable switch; only the CLI auto-enables.
    assertNull(envFactory.create(AiSettings()))
  }

  @Test
  fun `platform credentials fill the selected provider's blank token`() {
    val resolved = envFactory.withPlatformCredentials(AiSettings())
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
}
