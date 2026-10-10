package com.linroid.ketch.app

import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.AiConnectionTest
import com.linroid.ketch.app.state.AiDiscoverFailure
import com.linroid.ketch.app.state.AiDiscoveryProvider
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.AiModelList
import com.linroid.ketch.app.state.AiSettingsController
import com.linroid.ketch.app.state.TurnModel
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.KetchConfig
import com.linroid.ketch.config.LlmProvider
import com.linroid.ketch.config.LlmSettings
import com.linroid.ketch.config.PageAccessMode
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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

/** Stands in for an API key exported in the environment, which fills every blank key. */
private val envToken: (AiSettings) -> AiSettings = { settings ->
  settings.copy(
    providers = settings.entries.map { it.copy(apiKey = it.apiKey.ifBlank { "sk-env" }) },
  )
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
  fun saveAccess_keepsTheProviderAndPersistsTheAccess() {
    val store = RecordingConfigStore(KetchConfig(ai = usableSettings()))
    val factory = FakeFactory()
    val controller = AiSettingsController(store, factory)
    val first = controller.provider as FakeAiProvider
    controller.saveAccess { it.copy(mode = PageAccessMode.AskEveryTime).trusting("ubuntu.com") }
    assertSame(first, controller.provider)
    assertFalse(first.closed, "a search running on the provider should go on")
    assertEquals(1, factory.created.size)
    val saved = store.load().ai.access
    assertEquals(PageAccessMode.AskEveryTime, saved.mode)
    assertEquals(listOf("ubuntu.com"), saved.trustedSites)
  }

  @Test
  fun save_sameEngineSettings_keepsTheProvider() {
    val store = RecordingConfigStore(KetchConfig(ai = usableSettings()))
    val factory = FakeFactory()
    val controller = AiSettingsController(store, factory)
    val first = controller.provider as FakeAiProvider
    controller.save(usableSettings())
    assertSame(first, controller.provider)
    assertFalse(first.closed)
    assertEquals(1, factory.created.size)
  }

  @Test
  fun save_contentFilterOff_keepsTheProviderAndPersistsIt() {
    val store = RecordingConfigStore(KetchConfig(ai = usableSettings()))
    val factory = FakeFactory()
    val controller = AiSettingsController(store, factory)
    val first = controller.provider as FakeAiProvider
    controller.save(usableSettings().copy(contentFilter = false))
    assertSame(first, controller.provider)
    assertEquals(1, factory.created.size)
    assertFalse(store.load().ai.contentFilter)
  }

  @Test
  fun saveAccess_unchanged_savesNothing() {
    val store = RecordingConfigStore(KetchConfig(ai = usableSettings()))
    val controller = AiSettingsController(store, FakeFactory())
    controller.saveAccess { it }
    assertEquals(0, store.saves)
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
    controller.save(AiSettings(enabled = true))
    controller.testConnection()
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
    controller.save(usableSettings().copy(enabled = false))
    controller.testConnection()
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
    controller.save(usableSettings())
    controller.testConnection()
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
    controller.save(usableSettings())
    controller.testConnection()
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
    controller.save(AiSettings(enabled = true))
    controller.testConnection()
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
    controller.save(usableSettings())
    controller.testConnection()
    assertTrue(controller.connectionTest is AiConnectionTest.Success)
    controller.save(usableSettings(apiKey = "sk-other"))
    assertEquals(AiConnectionTest.Idle, controller.connectionTest)
  }

  @Test
  fun untouchedSettingsWithAnEnvironmentToken_makeDiscoveryAvailable() {
    // Discovery is on by default: a key is all it waits for.
    val controller = AiSettingsController(RecordingConfigStore(), FakeFactory(platform = envToken))
    assertTrue(controller.available)
  }

  @Test
  fun setEnabled_off_releasesTheProviderAndPersistsTheSwitch() {
    val store = RecordingConfigStore(KetchConfig(ai = usableSettings()))
    val controller = AiSettingsController(store, FakeFactory())
    val running = controller.provider as FakeAiProvider

    controller.setEnabled(false)

    assertFalse(controller.available)
    assertTrue(running.closed)
    assertEquals(usableSettings().copy(enabled = false), store.load().ai)
  }

  @Test
  fun setEnabled_backOn_buildsAProviderAgain() {
    val store = RecordingConfigStore(KetchConfig(ai = usableSettings().copy(enabled = false)))
    val controller = AiSettingsController(store, FakeFactory())

    controller.setEnabled(true)

    assertTrue(controller.available)
    assertTrue(store.load().ai.enabled)
  }

  @Test
  fun offered_switchedOnWithoutKeys_offersDiscoverToSetUp() {
    val controller = AiSettingsController(RecordingConfigStore(), FakeFactory())
    assertTrue(controller.offered)
    assertFalse(controller.available)

    controller.setEnabled(false)

    assertFalse(controller.offered)
  }

  @Test
  fun offered_unsupported_neverOffersDiscover() {
    assertFalse(AiSettingsController(RecordingConfigStore()).offered)
  }

  @Test
  fun chooseProvider_newProvider_switchesDiscoveryOnAndKeepsTheOtherOne() {
    val openAi = LlmSettings(
      provider = LlmProvider.OpenAi,
      apiKey = "sk-test",
      model = "gpt-custom",
      baseUrl = "https://proxy.example.com",
    )
    val store = RecordingConfigStore(KetchConfig(ai = AiSettings(enabled = false, llm = openAi)))
    val controller = AiSettingsController(store, FakeFactory())

    controller.chooseProvider(LlmProvider.Anthropic)

    val saved = store.load().ai
    assertTrue(saved.enabled)
    assertEquals(LlmProvider.Anthropic, saved.llm.provider)
    assertEquals("", saved.llm.apiKey)
    // Going back finds the key, model and endpoint as they were.
    controller.chooseProvider(LlmProvider.OpenAi)
    assertEquals(openAi.copy(id = "openai"), controller.settings.llm)
    assertEquals(2, controller.settings.providers.size)
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
  fun addProvider_keepsTheOneInUseAndItsSearches() {
    val controller = AiSettingsController(
      RecordingConfigStore(KetchConfig(ai = usableSettings())),
      FakeFactory(),
    )
    val running = controller.provider

    val id = controller.addProvider(LlmProvider.OpenAi)

    assertEquals("openai-2", id)
    assertEquals("openai", controller.settings.llm.id)
    assertSame(running, controller.provider)
  }

  @Test
  fun use_otherProviderAndModel_rebuildsTheProviderForTheNextSearch() {
    val settings = usableSettings()
      .withEntry(LlmSettings(id = "work", provider = LlmProvider.Mistral, apiKey = "k"))
    val factory = FakeFactory()
    val controller = AiSettingsController(RecordingConfigStore(KetchConfig(ai = settings)), factory)
    val first = controller.provider as FakeAiProvider

    controller.use("work", "mistral-small-latest")

    assertEquals("work", controller.settings.llm.id)
    assertEquals("mistral-small-latest", controller.settings.llm.effectiveModel)
    assertEquals(TurnModel("Mistral", "mistral-small-latest"), controller.activeModel)
    assertTrue(first.closed)
    assertEquals(2, factory.created.size)
  }

  @Test
  fun removeProvider_inUse_handsOverToTheFirstOneLeft() {
    val settings = usableSettings()
      .withEntry(LlmSettings(id = "work", provider = LlmProvider.Mistral, apiKey = "k"))
      .withActive("work")
    val store = RecordingConfigStore(KetchConfig(ai = settings))
    val controller = AiSettingsController(store, FakeFactory())

    controller.removeProvider("work")

    assertEquals("openai", store.load().ai.llm.id)
    assertTrue(controller.available)
  }

  @Test
  fun testConnection_providerNotInUse_testsItOnATemporaryEngine() = runTest {
    val settings = usableSettings()
      .withEntry(LlmSettings(id = "work", provider = LlmProvider.Mistral, apiKey = "k"))
    val factory = FakeFactory()
    val controller = AiSettingsController(RecordingConfigStore(KetchConfig(ai = settings)), factory)
    val inUse = controller.provider

    controller.testConnection("work")

    assertEquals("work", controller.testedId)
    assertEquals(AiConnectionTest.Success("OK"), controller.connectionTest)
    assertSame(inUse, controller.provider)
    assertTrue((factory.created.last() as FakeAiProvider).closed)
    assertEquals("openai", controller.settings.llm.id)
  }

  @Test
  fun loadModels_listsThemPerProvider() = runTest {
    val factory = object : AiDiscoveryProviderFactory {
      override fun create(settings: AiSettings): AiDiscoveryProvider = FakeAiProvider()
      override suspend fun listModels(llm: LlmSettings): List<String> {
        if (llm.apiKey == "bad") throw AiDiscoverFailure("Rejected (HTTP 401)", "Rejected")
        return listOf("a", "b")
      }
    }
    val settings = usableSettings()
      .withEntry(LlmSettings(id = "bad", provider = LlmProvider.Mistral, apiKey = "bad"))
    val controller = AiSettingsController(RecordingConfigStore(KetchConfig(ai = settings)), factory)

    controller.loadModels("openai")
    controller.loadModels("bad")

    assertEquals(AiModelList.Loaded(listOf("a", "b")), controller.modelLists["openai"])
    assertEquals(AiModelList.Failed(verbatim("Rejected (HTTP 401)")), controller.modelLists["bad"])
  }

  @Test
  fun chooseProvider_providerWithoutAKey_makesDiscoveryAvailable() {
    val controller = AiSettingsController(RecordingConfigStore(), FakeFactory())
    assertFalse(controller.available)

    controller.chooseProvider(LlmProvider.Ollama)

    assertTrue(controller.available)
  }
}
