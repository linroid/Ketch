package com.linroid.ketch.app.state

import com.linroid.ketch.ai.AiConfig
import com.linroid.ketch.ai.AiModule
import com.linroid.ketch.ai.DiscoverQuery
import com.linroid.ketch.ai.agent.DiscoveryStepListener
import com.linroid.ketch.ai.resolveAiSettingsFromEnv
import com.linroid.ketch.config.AiSettings
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * In-process AI discovery using the `ai:discover` module directly.
 * Available on JVM/Desktop where the AI module can run.
 *
 * Searches run one at a time, so the steps the module reports belong to the search that asked
 * for them.
 */
class EmbeddedAiDiscoveryProvider internal constructor(
  private val module: AiModule,
  private val steps: StepRelay,
) : AiDiscoveryProvider {
  private val runs = Mutex()

  override suspend fun discover(
    request: AiDiscoverRequest,
    onStep: (DiscoveryStep) -> Unit,
  ): AiDiscoverResponse = runs.withLock {
    steps.target = onStep
    try {
      search(request)
    } finally {
      steps.target = null
    }
  }

  private suspend fun search(request: AiDiscoverRequest): AiDiscoverResponse {
    val result = module.discoveryService.discover(
      DiscoverQuery(
        query = request.query,
        sites = request.sites,
        maxResults = request.maxResults,
        fileTypes = request.fileTypes,
      )
    )
    return AiDiscoverResponse(
      query = result.query,
      candidates = result.candidates.map { c ->
        AiCandidate(
          url = c.url,
          title = c.title,
          fileName = c.fileName,
          fileSize = c.fileSize,
          mimeType = c.mimeType,
          sourceUrl = c.sourceUrl,
          confidence = c.confidence,
          description = c.description,
        )
      },
    )
  }

  override suspend fun verify(): String =
    module.discoveryService.verifyConnection()

  override fun close() {
    module.close()
  }
}

/** Hands the steps the module reports to the search running now, if any. */
internal class StepRelay : DiscoveryStepListener {
  @Volatile
  var target: ((DiscoveryStep) -> Unit)? = null

  override fun onStep(title: String, details: String) {
    target?.invoke(DiscoveryStep(title = title.trim(), detail = details.trim()))
  }
}

/**
 * Builds [EmbeddedAiDiscoveryProvider] instances from saved settings,
 * filling blank credentials from the environment.
 *
 * Unlike the CLI, the apps show an explicit Enable switch, so disabled
 * settings never produce a provider — an environment key only fills in
 * the token once the user has switched discovery on.
 *
 * @param getenv environment lookup, overridable for testing.
 */
class EmbeddedAiDiscoveryProviderFactory(
  private val getenv: (String) -> String? = System::getenv,
) : AiDiscoveryProviderFactory {

  override fun create(settings: AiSettings): AiDiscoveryProvider? {
    if (!settings.enabled) return null
    val resolved = withPlatformCredentials(settings)
    if (!resolved.isUsable) return null
    val steps = StepRelay()
    return EmbeddedAiDiscoveryProvider(
      module = AiModule.create(AiConfig(settings = resolved), stepListener = steps),
      steps = steps,
    )
  }

  override fun withPlatformCredentials(settings: AiSettings): AiSettings =
    // Resolved as switched on, so the environment only fills blanks: the
    // untouched-settings shortcut that picks a provider and enables the
    // feature is reserved for the CLI.
    resolveAiSettingsFromEnv(settings.copy(enabled = true), getenv)
      .copy(enabled = settings.enabled)
}
