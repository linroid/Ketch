package com.linroid.ketch.app.state

import com.linroid.ketch.ai.DiscoverQuery
import com.linroid.ketch.ai.ResourceDiscoveryService

/**
 * In-process AI discovery using the `library:ai` module directly.
 * Available on JVM/Desktop where the AI module can run.
 */
class EmbeddedAiDiscoveryProvider(
  private val discoveryService: ResourceDiscoveryService,
) : AiDiscoveryProvider {

  override suspend fun discover(
    request: AiDiscoverRequest,
  ): AiDiscoverResponse {
    val result = discoveryService.discover(
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
}
