package com.linroid.ketch.app

import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.AiConnectionTest
import com.linroid.ketch.app.state.AiDiscoveryProvider
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.AiSettingsController
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Builds a provider only for settings it considers usable, after
 * applying [platform] the way a real factory fills environment keys.
 */
private class FakeFactory(
  private val platform: (AiSettings) -> AiSettings = { it },
  private val usable: (AiSettings) -> Boolean = { it.isUsable },
  private val provider: () -> AiDiscoveryProvider = { FakeAiProvider() },
) : AiDiscoveryProviderFactory {
  val created = mutableListOf<AiDiscoveryProvider>()

  override fun create(settings: AiSettings): AiDiscoveryProvider? {
    if (!usable(platform(settings))) return null
    return provider().also { created += it }
  }

  override fun withPlatformCredentials(settings: AiSettings): AiSettings =
    platform(settings)
}

/** Stands in for an API key exported in the environment. */
private val envToken: (AiSettings) -> AiSettings = {
  it.copy(llm = it.llm.copy(apiKey = it.llm.apiKey.ifBlank { "sk-env" }))
}

private fun usableSettings(apiKey: String = "sk-test") = AiSettings(
  enabled = true,
  llm = LlmSettings(provider = LlmProvider.OpenAi, apiKey = apiKey),
)

class AiSettingsControllerTest {

  @Test
  fun initialSettingsComeFromTheStore() {
    val stored = usableSettings()
    val controller = AiSettingsController(
      configStore = RecordingConfigStore(KetchConfig(ai = stored)),
      factory = FakeFactory(),
    )
    assertEquals(stored, controller.settings)
    assertTrue(controller.available)
  }

  @Test
  fun withoutAFactoryDiscoveryIsUnsupported() {
    val controller = AiSettingsController(
      configStore = RecordingConfigStore(KetchConfig(ai = usableSettings())),
    )
    assertFalse(controller.supported)
    assertNull(controller.provider)
  }

  @Test
  fun incompleteSettingsLeaveNoProvider() {
    val controller = AiSettingsController(
      configStore = RecordingConfigStore(),
      factory = FakeFactory(),
    )
    assertTrue(controller.supported)
    assertNull(controller.provider)
    assertFalse(controller.available)
  }

  @Test
  fun savingCredentialsRebuildsTheProviderAndPersistsThem() {
    val store = RecordingConfigStore()
    val controller = AiSettingsController(store, FakeFactory())
    controller.save(usableSettings())
    assertEquals(1, store.saves)
    assertEquals(usableSettings(), store.load().ai)
    assertNotNull(controller.provider)
  }

  @Test
  fun savingKeepsUnrelatedConfigSections() {
    val store = RecordingConfigStore(KetchConfig(name = "laptop"))
    val controller = AiSettingsController(store, FakeFactory())
    controller.save(usableSettings())
    assertEquals("laptop", store.load().name)
  }

  @Test
  fun rebuildingReleasesTheReplacedProvider() {
    val store = RecordingConfigStore(KetchConfig(ai = usableSettings()))
    val controller = AiSettingsController(store, FakeFactory())
    val first = controller.provider as FakeAiProvider
    controller.save(usableSettings(apiKey = "sk-other"))
    assertTrue(first.closed, "the superseded provider should be closed")
    assertFalse((controller.provider as FakeAiProvider).closed)
  }

  @Test
  fun platformCredentialsComeFromTheFactory() {
    val controller = AiSettingsController(
      configStore = RecordingConfigStore(),
      factory = FakeFactory(platform = envToken),
    )
    val resolved = controller.withPlatformCredentials(AiSettings())
    assertEquals("sk-env", resolved.llm.apiKey)
  }

  @Test
  fun withoutAFactorySettingsPassThroughUnchanged() {
    val controller = AiSettingsController(RecordingConfigStore())
    val settings = AiSettings(enabled = true)
    assertEquals(settings, controller.withPlatformCredentials(settings))
  }

  @Test
  fun anEnvironmentTokenMakesSwitchedOnDiscoveryAvailable() {
    val controller = AiSettingsController(
      configStore = RecordingConfigStore(KetchConfig(ai = AiSettings(enabled = true))),
      factory = FakeFactory(platform = envToken),
    )
    assertTrue(controller.available)
  }

  @Test
  fun testConnectionUsesAnEnvironmentToken() = runTest {
    // Review case: a blank saved token with the key exported used to
    // leave Test connection unusable.
    val controller = AiSettingsController(
      configStore = RecordingConfigStore(),
      factory = FakeFactory(platform = envToken),
    )
    controller.testConnection(AiSettings(enabled = true))
    assertEquals(AiConnectionTest.Success("OK"), controller.connectionTest)
  }

  @Test
  fun closeReleasesTheProvider() {
    // Review case: a discarded controller must not leak its engine.
    val controller = AiSettingsController(
      configStore = RecordingConfigStore(KetchConfig(ai = usableSettings())),
      factory = FakeFactory(),
    )
    val running = controller.provider as FakeAiProvider
    controller.close()
    assertTrue(running.closed)
    assertNull(controller.provider)
    assertFalse(controller.available)
  }

  @Test
  fun switchedOffDiscoveryStaysHiddenEvenWithEnvironmentCredentials() {
    // Regression: an exported OPENAI_API_KEY used to switch discovery
    // (and the Discover tab) on while the Enable switch showed off.
    val factory = FakeFactory(usable = { true })
    val controller = AiSettingsController(
      configStore = RecordingConfigStore(KetchConfig(ai = AiSettings(enabled = false))),
      factory = factory,
    )
    assertNull(controller.provider)
    assertFalse(controller.available)
    assertTrue(factory.created.isEmpty(), "no engine for a disabled feature")
  }

  @Test
  fun switchingDiscoveryOffReleasesTheProvider() {
    val store = RecordingConfigStore(KetchConfig(ai = usableSettings()))
    val controller = AiSettingsController(store, FakeFactory())
    val running = controller.provider as FakeAiProvider
    controller.save(usableSettings().copy(enabled = false))
    assertNull(controller.provider)
    assertTrue(running.closed)
  }

  @Test
  fun testConnectionWorksWhileDiscoveryIsSwitchedOff() = runTest {
    val factory = FakeFactory(usable = { it.llm.isComplete })
    val controller = AiSettingsController(RecordingConfigStore(), factory)
    controller.testConnection(usableSettings().copy(enabled = false))
    assertEquals(AiConnectionTest.Success("OK"), controller.connectionTest)
    // The check must not switch discovery on behind the user's back.
    assertNull(controller.provider)
    assertTrue((factory.created.single() as FakeAiProvider).closed)
  }

  @Test
  fun testConnectionReportsTheModelReply() = runTest {
    val controller = AiSettingsController(
      configStore = RecordingConfigStore(),
      factory = FakeFactory(provider = { FakeAiProvider(reply = "OK") }),
    )
    controller.testConnection(usableSettings())
    assertEquals(AiConnectionTest.Success("OK"), controller.connectionTest)
  }

  @Test
  fun testConnectionReportsProviderFailures() = runTest {
    val controller = AiSettingsController(
      configStore = RecordingConfigStore(),
      factory = FakeFactory(
        provider = {
          FakeAiProvider(verifyFailure = IllegalStateException("401 no key"))
        },
      ),
    )
    controller.testConnection(usableSettings())
    assertEquals(
      AiConnectionTest.Failure(verbatim("401 no key")), controller.connectionTest,
    )
  }

  @Test
  fun testConnectionWithoutAProviderAsksForTheMissingFields() = runTest {
    val controller = AiSettingsController(
      configStore = RecordingConfigStore(),
      factory = FakeFactory(),
    )
    controller.testConnection(AiSettings(enabled = true))
    val result = controller.connectionTest
    assertTrue(result is AiConnectionTest.Failure)
    assertTrue(result.message.load().contains("Fill in"))
  }

  @Test
  fun savingClearsAStaleTestResult() = runTest {
    val controller = AiSettingsController(
      configStore = RecordingConfigStore(),
      factory = FakeFactory(),
    )
    controller.testConnection(usableSettings())
    assertTrue(controller.connectionTest is AiConnectionTest.Success)
    controller.save(usableSettings(apiKey = "sk-other"))
    assertEquals(AiConnectionTest.Idle, controller.connectionTest)
  }

  @Test
  fun chooseProvider_newProvider_switchesDiscoveryOnWithItsDefaults() {
    val store = RecordingConfigStore(
      KetchConfig(
        ai = AiSettings(
          llm = LlmSettings(
            provider = LlmProvider.OpenAi,
            apiKey = "sk-test",
            model = "gpt-custom",
            baseUrl = "https://proxy.example.com",
          ),
        ),
      ),
    )
    val controller = AiSettingsController(store, FakeFactory())

    controller.chooseProvider(LlmProvider.Anthropic)

    val saved = store.load().ai
    assertTrue(saved.enabled)
    assertEquals(LlmSettings(provider = LlmProvider.Anthropic, apiKey = "sk-test"), saved.llm)
  }

  @Test
  fun chooseProvider_sameProvider_keepsModelAndEndpoint() {
    val llm = LlmSettings(provider = LlmProvider.OpenAi, model = "gpt-custom")
    val controller = AiSettingsController(
      configStore = RecordingConfigStore(KetchConfig(ai = AiSettings(llm = llm))),
      factory = FakeFactory(),
    )

    controller.chooseProvider(LlmProvider.OpenAi)

    assertEquals(AiSettings(enabled = true, llm = llm), controller.settings)
  }

  @Test
  fun chooseProvider_providerWithoutAKey_makesDiscoveryAvailable() {
    val controller = AiSettingsController(RecordingConfigStore(), FakeFactory())
    assertFalse(controller.available)

    controller.chooseProvider(LlmProvider.Ollama)

    assertTrue(controller.available)
  }
}
