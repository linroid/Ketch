package com.linroid.ketch.app.state

import com.linroid.ketch.ai.DiscoverQuery
import com.linroid.ketch.ai.ResourceDiscoveryService
import com.linroid.ketch.endpoints.model.DiscoverRequest
import com.linroid.ketch.endpoints.model.DiscoverResponse
import com.linroid.ketch.endpoints.model.ResourceCandidate
import com.linroid.ketch.endpoints.model.SourceReference

/**
 * In-process AI discovery using the `library:ai` module directly.
 * Available on Android where the AI module can run.
 */
class EmbeddedAiDiscoveryProvider(
  private val discoveryService: ResourceDiscoveryService,
) : AiDiscoveryProvider {

  override suspend fun discover(
    request: DiscoverRequest,
  ): DiscoverResponse {
    val result = discoveryService.discover(
      DiscoverQuery(
        query = request.query,
        sites = request.sites,
        maxResults = request.maxResults,
        fileTypes = request.fileTypes,
      )
    )
    return DiscoverResponse(
      query = result.query,
      candidates = result.candidates.map { c ->
        ResourceCandidate(
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
      sources = result.sources.map { s ->
        SourceReference(
          url = s.url,
          title = s.title,
          fetchedAt = s.fetchedAt,
        )
      },
    )
  }
}
