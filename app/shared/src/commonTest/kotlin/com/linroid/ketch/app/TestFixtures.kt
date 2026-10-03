package com.linroid.ketch.app

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.KetchStatus
import com.linroid.ketch.api.SystemInfo
import com.linroid.ketch.app.instance.InstanceFactory
import com.linroid.ketch.app.instance.InstanceManager
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.AiDiscoverRequest
import com.linroid.ketch.app.state.AiDiscoverResponse
import com.linroid.ketch.app.state.AiDiscoveryProvider
import com.linroid.ketch.app.state.AppController
import com.linroid.ketch.app.state.DiscoveryStep
import com.linroid.ketch.config.ConfigStore
import com.linroid.ketch.config.KetchConfig
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlin.coroutines.CoroutineContext

/**
 * Runs [block] over the fixture [create] makes and [close]s it even when an assertion fails,
 * since the timers of an open app would otherwise keep the test's scheduler from going idle.
 */
internal fun <F> fixtureTest(
  create: TestScope.() -> F,
  close: (F) -> Unit,
  block: suspend TestScope.(F) -> Unit,
): TestResult = runTest {
  val fixture = create()
  try {
    block(fixture)
  } finally {
    close(fixture)
  }
}

/** An app over one device, "[deviceName]", whose engine is [api], run by the test's scheduler. */
internal fun TestScope.testController(
  api: KetchApi,
  configStore: ConfigStore? = null,
  deviceName: String = "This Mac",
  context: CoroutineContext = StandardTestDispatcher(testScheduler),
): AppController = AppController(
  instanceManager = InstanceManager(
    factory = InstanceFactory(deviceName = deviceName, embeddedFactory = { api }),
    configStore = configStore,
  ),
  context = context,
)

/**
 * A context for an app under test whose loops run as a child of the background scope, so a
 * failed assertion never leaves them running.
 */
internal fun TestScope.backgroundChild(): CoroutineContext =
  backgroundScope.coroutineContext + SupervisorJob(backgroundScope.coroutineContext[Job])

/** The status a test device reports, `test` placeholders unless given. */
internal fun testStatus(
  name: String,
  config: DownloadConfig = DownloadConfig(),
  version: String = "test",
  revision: String = "test",
  uptime: Long = 0,
  system: SystemInfo = testSystem(),
  features: Set<String> = emptySet(),
): KetchStatus = KetchStatus(name, version, revision, uptime, config, system, features)

/** The system a test device runs on, `test` placeholders and no memory unless given. */
internal fun testSystem(
  os: String = "test",
  arch: String = "test",
  javaVersion: String = "N/A",
  availableProcessors: Int = 1,
  downloadDirectory: String = "/downloads",
  totalSpace: Long = 0,
  freeSpace: Long = 0,
  usableSpace: Long = 0,
): SystemInfo = SystemInfo(
  os = os,
  arch = arch,
  separator = "/",
  javaVersion = javaVersion,
  availableProcessors = availableProcessors,
  maxMemory = 0,
  totalMemory = 0,
  freeMemory = 0,
  downloadDirectory = downloadDirectory,
  totalSpace = totalSpace,
  freeSpace = freeSpace,
  usableSpace = usableSpace,
)

/** Keeps the config in memory and counts how often it is saved. */
internal class RecordingConfigStore(config: KetchConfig = KetchConfig()) : ConfigStore {
  /** The config last saved, or the one it started with. */
  var config: KetchConfig = config
    private set

  var saves = 0
    private set

  override fun load(): KetchConfig = config

  override fun save(config: KetchConfig) {
    this.config = config
    saves++
  }
}

/**
 * An AI provider that answers every search with [candidates], or throws [failure], reporting
 * [steps] first and waiting for [gate]; its connection test answers [reply] or throws
 * [verifyFailure].
 */
internal class FakeAiProvider(
  private val steps: List<DiscoveryStep> = emptyList(),
  private val candidates: List<AiCandidate> = emptyList(),
  private val gate: CompletableDeferred<Unit>? = null,
  private val failure: Exception? = null,
  private val reply: String = "OK",
  private val verifyFailure: Throwable? = null,
) : AiDiscoveryProvider {
  val requests = mutableListOf<AiDiscoverRequest>()

  var closed = false
    private set

  override suspend fun discover(
    request: AiDiscoverRequest,
    onStep: (DiscoveryStep) -> Unit,
  ): AiDiscoverResponse {
    requests += request
    steps.forEach(onStep)
    gate?.await()
    failure?.let { throw it }
    return AiDiscoverResponse(request.query, candidates)
  }

  override suspend fun verify(): String = verifyFailure?.let { throw it } ?: reply

  override fun close() {
    closed = true
  }
}
