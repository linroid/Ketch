package com.linroid.ketch.app.state

import com.linroid.ketch.ai.AiConfig
import com.linroid.ketch.ai.AiModule
import com.linroid.ketch.ai.DiscoverQuery
import com.linroid.ketch.ai.resolveAiSettingsFromEnv
import com.linroid.ketch.config.AiSettings

/**
 * In-process AI discovery using the `ai:discover` module directly.
 * Available on JVM/Desktop where the AI module can run.
 */
class EmbeddedAiDiscoveryProvider(
  private val module: AiModule,
) : AiDiscoveryProvider {

  override suspend fun discover(
    request: AiDiscoverRequest,
  ): AiDiscoverResponse {
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
    return EmbeddedAiDiscoveryProvider(
      AiModule.create(AiConfig(settings = resolved)),
    )
  }

  override fun withPlatformCredentials(settings: AiSettings): AiSettings =
    // Resolved as switched on, so the environment only fills blanks: the
    // untouched-settings shortcut that picks a provider and enables the
    // feature is reserved for the CLI.
    resolveAiSettingsFromEnv(settings.copy(enabled = true), getenv)
      .copy(enabled = settings.enabled)
}
