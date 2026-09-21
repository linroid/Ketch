package com.linroid.ketch.app

import com.linroid.ketch.app.state.AiConnectionTest
import com.linroid.ketch.app.state.AiDiscoverRequest
import com.linroid.ketch.app.state.AiDiscoverResponse
import com.linroid.ketch.app.state.AiDiscoveryProvider
import com.linroid.ketch.app.state.AiDiscoveryProviderFactory
import com.linroid.ketch.app.state.AiSettingsController
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.config.ConfigStore
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

private class FakeConfigStore(
  private var config: KetchConfig = KetchConfig(),
) : ConfigStore {
  var saves = 0
    private set

  override fun load(): KetchConfig = config

  override fun save(config: KetchConfig) {
    this.config = config
    saves++
  }
}

private class FakeAiProvider(
  private val reply: String = "OK",
  private val failure: Throwable? = null,
) : AiDiscoveryProvider {
  var closed = false
    private set

  override suspend fun discover(
    request: AiDiscoverRequest,
  ): AiDiscoverResponse = AiDiscoverResponse(request.query, emptyList())

  override suspend fun verify(): String = failure?.let { throw it } ?: reply

  override fun close() {
    closed = true
  }
}

/** Builds a provider only for settings it considers usable. */
private class FakeFactory(
  private val usable: (AiSettings) -> Boolean = { it.isUsable },
  private val provider: () -> AiDiscoveryProvider = { FakeAiProvider() },
) : AiDiscoveryProviderFactory {
  val created = mutableListOf<AiDiscoveryProvider>()

  override fun create(settings: AiSettings): AiDiscoveryProvider? {
    if (!usable(settings)) return null
    return provider().also { created += it }
  }
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
      configStore = FakeConfigStore(KetchConfig(ai = stored)),
      factory = FakeFactory(),
    )
    assertEquals(stored, controller.settings)
    assertTrue(controller.available)
  }

  @Test
  fun withoutAFactoryDiscoveryIsUnsupported() {
    val controller = AiSettingsController(
      configStore = FakeConfigStore(KetchConfig(ai = usableSettings())),
    )
    assertFalse(controller.supported)
    assertNull(controller.provider)
  }

  @Test
  fun incompleteSettingsLeaveNoProvider() {
    val controller = AiSettingsController(
      configStore = FakeConfigStore(),
      factory = FakeFactory(),
    )
    assertTrue(controller.supported)
    assertNull(controller.provider)
    assertFalse(controller.available)
  }

  @Test
  fun savingCredentialsRebuildsTheProviderAndPersistsThem() {
    val store = FakeConfigStore()
    val controller = AiSettingsController(store, FakeFactory())
    controller.save(usableSettings())
    assertEquals(1, store.saves)
    assertEquals(usableSettings(), store.load().ai)
    assertNotNull(controller.provider)
  }

  @Test
  fun savingKeepsUnrelatedConfigSections() {
    val store = FakeConfigStore(KetchConfig(name = "laptop"))
    val controller = AiSettingsController(store, FakeFactory())
    controller.save(usableSettings())
    assertEquals("laptop", store.load().name)
  }

  @Test
  fun rebuildingReleasesTheReplacedProvider() {
    val store = FakeConfigStore(KetchConfig(ai = usableSettings()))
    val controller = AiSettingsController(store, FakeFactory())
    val first = controller.provider as FakeAiProvider
    controller.save(usableSettings(apiKey = "sk-other"))
    assertTrue(first.closed, "the superseded provider should be closed")
    assertFalse((controller.provider as FakeAiProvider).closed)
  }

  @Test
  fun credentialsFromTheEnvironmentAreReported() {
    // Switched on without a saved token, the factory still succeeds —
    // which is what an environment-provided key looks like.
    val controller = AiSettingsController(
      configStore = FakeConfigStore(KetchConfig(ai = AiSettings(enabled = true))),
      factory = FakeFactory(usable = { true }),
    )
    assertTrue(controller.available)
    assertTrue(controller.usingEnvironmentCredentials)
  }

  @Test
  fun switchedOffDiscoveryStaysHiddenEvenWithEnvironmentCredentials() {
    // Regression: an exported OPENAI_API_KEY used to switch discovery
    // (and the Discover tab) on while the Enable switch showed off.
    val factory = FakeFactory(usable = { true })
    val controller = AiSettingsController(
      configStore = FakeConfigStore(KetchConfig(ai = AiSettings(enabled = false))),
      factory = factory,
    )
    assertNull(controller.provider)
    assertFalse(controller.available)
    assertTrue(factory.created.isEmpty(), "no engine for a disabled feature")
  }

  @Test
  fun switchingDiscoveryOffReleasesTheProvider() {
    val store = FakeConfigStore(KetchConfig(ai = usableSettings()))
    val controller = AiSettingsController(store, FakeFactory())
    val running = controller.provider as FakeAiProvider
    controller.save(usableSettings().copy(enabled = false))
    assertNull(controller.provider)
    assertTrue(running.closed)
  }

  @Test
  fun testConnectionWorksWhileDiscoveryIsSwitchedOff() = runTest {
    val factory = FakeFactory(usable = { it.llm.isComplete })
    val controller = AiSettingsController(FakeConfigStore(), factory)
    controller.testConnection(usableSettings().copy(enabled = false))
    assertEquals(AiConnectionTest.Success("OK"), controller.connectionTest)
    // The check must not switch discovery on behind the user's back.
    assertNull(controller.provider)
    assertTrue((factory.created.single() as FakeAiProvider).closed)
  }

  @Test
  fun configuredCredentialsAreNotReportedAsEnvironmentOnes() {
    val controller = AiSettingsController(
      configStore = FakeConfigStore(KetchConfig(ai = usableSettings())),
      factory = FakeFactory(),
    )
    assertFalse(controller.usingEnvironmentCredentials)
  }

  @Test
  fun testConnectionReportsTheModelReply() = runTest {
    val controller = AiSettingsController(
      configStore = FakeConfigStore(),
      factory = FakeFactory(provider = { FakeAiProvider(reply = "OK") }),
    )
    controller.testConnection(usableSettings())
    assertEquals(AiConnectionTest.Success("OK"), controller.connectionTest)
  }

  @Test
  fun testConnectionReportsProviderFailures() = runTest {
    val controller = AiSettingsController(
      configStore = FakeConfigStore(),
      factory = FakeFactory(
        provider = {
          FakeAiProvider(failure = IllegalStateException("401 no key"))
        },
      ),
    )
    controller.testConnection(usableSettings())
    assertEquals(
      AiConnectionTest.Failure("401 no key"), controller.connectionTest,
    )
  }

  @Test
  fun testConnectionWithoutAProviderAsksForTheMissingFields() = runTest {
    val controller = AiSettingsController(
      configStore = FakeConfigStore(),
      factory = FakeFactory(),
    )
    controller.testConnection(AiSettings(enabled = true))
    val result = controller.connectionTest
    assertTrue(result is AiConnectionTest.Failure)
    assertTrue(result.message.contains("Fill in"))
  }

  @Test
  fun savingClearsAStaleTestResult() = runTest {
    val controller = AiSettingsController(
      configStore = FakeConfigStore(),
      factory = FakeFactory(),
    )
    controller.testConnection(usableSettings())
    assertTrue(controller.connectionTest is AiConnectionTest.Success)
    controller.save(usableSettings(apiKey = "sk-other"))
    assertEquals(AiConnectionTest.Idle, controller.connectionTest)
  }
}
