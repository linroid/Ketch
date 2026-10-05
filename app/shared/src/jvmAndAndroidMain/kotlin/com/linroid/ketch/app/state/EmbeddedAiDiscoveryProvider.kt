package com.linroid.ketch.app.state

import com.linroid.ketch.ai.AiConfig
import com.linroid.ketch.ai.AiModule
import com.linroid.ketch.ai.DiscoverDevice
import com.linroid.ketch.ai.DiscoverQuery
import com.linroid.ketch.ai.DiscoverResult
import com.linroid.ketch.ai.DiscoveryException
import com.linroid.ketch.ai.PageAccessApprover
import com.linroid.ketch.ai.PageAccessKind
import com.linroid.ketch.ai.agent.DiscoveryStepListener
import com.linroid.ketch.ai.resolveAiSettingsFromEnv
import com.linroid.ketch.config.AiSettings
import com.linroid.ketch.ai.DiscoverTurn as EngineTurn

/**
 * In-process AI discovery using the `ai:discover` module directly.
 * Available on Android and JVM/Desktop, where the AI module can run.
 *
 * Searches may run at the same time, each with its own step listener and approver. [close]
 * releases the module once the searches running then have ended, so replacing the provider after
 * a settings change never cuts a search short.
 */
class EmbeddedAiDiscoveryProvider internal constructor(
  private val search: suspend (DiscoverQuery, DiscoveryStepListener, PageAccessApprover) ->
    DiscoverResult,
  private val verifyConnection: suspend () -> String,
  private val release: () -> Unit,
) : AiDiscoveryProvider {
  private val lock = Any()
  private var active = 0
  private var closing = false

  internal constructor(module: AiModule) : this(
    search = module.discoveryService::discover,
    verifyConnection = module.discoveryService::verifyConnection,
    release = module::close,
  )

  override suspend fun discover(
    request: AiDiscoverRequest,
    onStep: (DiscoveryStep) -> Unit,
    approve: suspend (AiPageRequest) -> Boolean,
  ): AiDiscoverResponse {
    synchronized(lock) {
      check(!closing) { "The discovery engine was closed" }
      active++
    }
    try {
      val approver = PageAccessApprover { page ->
        approve(
          AiPageRequest(
            url = page.url,
            host = page.host,
            kind = when (page.kind) {
              PageAccessKind.Page -> AiPageKind.Page
              PageAccessKind.FileInfo -> AiPageKind.FileInfo
            },
            reason = page.reason,
            redirectFrom = page.redirectFrom,
          ),
        )
      }
      val result = search(request.toQuery(), RunStepListener(onStep), approver)
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
        summary = result.summary,
        title = result.title,
        filtered = result.filtered,
      )
    } catch (e: DiscoveryException) {
      throw AiDiscoverFailure(e.message, e.brief, e)
    } finally {
      val last = synchronized(lock) {
        active--
        closing && active == 0
      }
      if (last) release()
    }
  }

  override suspend fun verify(): String = verifyConnection()

  override fun close() {
    val idle = synchronized(lock) {
      if (closing) return
      closing = true
      active == 0
    }
    if (idle) release()
  }
}

/** The engine's query for this request, with what the earlier turns learned of their links. */
private fun AiDiscoverRequest.toQuery() = DiscoverQuery(
  query = query,
  sites = sites,
  maxResults = maxResults,
  fileTypes = fileTypes,
  history = history.map { turn ->
    EngineTurn(
      request = turn.request,
      sites = turn.sites,
      completed = turn.completed,
      results = turn.results.map {
        EngineTurn.Result(
          url = it.url,
          title = it.title,
          fileName = it.fileName,
          sizeBytes = it.fileSize,
          mimeType = it.mimeType,
          sourceUrl = it.sourceUrl,
          description = it.description,
          confidence = it.confidence,
        )
      },
    )
  },
  excludedUrls = excludedUrls,
  contentFilter = contentFilter,
  userDevice = devices.user?.toEngine(),
  downloadDevice = devices.download?.toEngine(),
)

private fun AiDevice.toEngine() = DiscoverDevice(os = os, arch = arch)

/** Hands the steps of one search to [onStep], trimmed. */
internal class RunStepListener(
  private val onStep: (DiscoveryStep) -> Unit,
) : DiscoveryStepListener {
  override fun onStep(title: String, details: String) {
    onStep(DiscoveryStep(title = title.trim(), detail = details.trim()))
  }
}

/**
 * Builds [EmbeddedAiDiscoveryProvider] instances from saved settings,
 * filling blank credentials from the environment.
 *
 * Disabled settings never produce a provider. Unlike the CLI, the
 * environment only fills blank credentials of the providers the settings
 * name, so the engine always runs what the settings page shows.
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
    return EmbeddedAiDiscoveryProvider(AiModule.create(AiConfig(settings = resolved)))
  }

  override fun withPlatformCredentials(settings: AiSettings): AiSettings =
    resolveAiSettingsFromEnv(settings, getenv, autoConfigure = false)
}
