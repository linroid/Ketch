package com.linroid.ketch.ai.server

import com.linroid.ketch.ai.DiscoverQuery
import com.linroid.ketch.ai.ResourceDiscoveryService
import com.linroid.ketch.ai.site.SiteProfileStore
import com.linroid.ketch.ai.site.SiteProfiler
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.AddSiteRequest
import com.linroid.ketch.endpoints.model.AiDownloadRequest
import com.linroid.ketch.endpoints.model.AiDownloadResponse
import com.linroid.ketch.endpoints.model.AiDownloadResult
import com.linroid.ketch.endpoints.model.DiscoverRequest
import com.linroid.ketch.endpoints.model.DiscoverResponse
import com.linroid.ketch.endpoints.model.ResourceCandidate
import com.linroid.ketch.endpoints.model.SiteEntry
import com.linroid.ketch.endpoints.model.SiteProfileDto
import com.linroid.ketch.endpoints.model.SitesResponse
import com.linroid.ketch.endpoints.model.SourceReference
import io.ktor.http.HttpStatusCode
import io.ktor.server.request.receive
import io.ktor.server.resources.delete
import io.ktor.server.resources.get
import io.ktor.server.resources.post
import io.ktor.server.response.respond
import io.ktor.server.routing.Route

private val log = KetchLogger("AiRoutes")

/**
 * Installs the `/api/ai` routes for AI resource discovery.
 */
internal fun Route.aiRoutes(
  ketch: KetchApi,
  discoveryService: ResourceDiscoveryService,
  siteProfiler: SiteProfiler,
  siteProfileStore: SiteProfileStore,
) {
  post<Api.Ai.Discover> {
    val request = call.receive<DiscoverRequest>()
    log.i { "POST /api/ai/discover query=\"${request.query}\"" }

    val result = discoveryService.discover(
      DiscoverQuery(
        query = request.query,
        sites = request.sites,
        maxResults = request.maxResults,
        fileTypes = request.fileTypes,
      )
    )

    val response = DiscoverResponse(
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
    log.d { "Discover: ${response.candidates.size} candidates" }
    call.respond(response)
  }

  post<Api.Ai.Download> {
    val request = call.receive<AiDownloadRequest>()
    log.i {
      "POST /api/ai/download: ${request.candidates.size} candidates"
    }
    val results = request.candidates.map { candidate ->
      try {
        val downloadRequest = DownloadRequest(
          url = candidate.url,
          destination = request.destination
            ?.let { Destination(it) },
          connections = request.connections,
        )
        val task = ketch.download(downloadRequest)
        AiDownloadResult(
          url = candidate.url,
          taskId = task.taskId,
          status = "queued",
        )
      } catch (e: Exception) {
        log.w(e) {
          "Failed to start download: ${candidate.url}"
        }
        AiDownloadResult(
          url = candidate.url,
          taskId = "",
          status = "failed",
        )
      }
    }
    call.respond(
      HttpStatusCode.Created,
      AiDownloadResponse(downloads = results),
    )
  }

  get<Api.Ai.Sites> {
    log.d { "GET /api/ai/sites" }
    val entries = siteProfileStore.getAll()
    call.respond(
      SitesResponse(
        sites = entries.map { it.toDto() },
      ),
    )
  }

  post<Api.Ai.Sites> {
    val request = call.receive<AddSiteRequest>()
    log.i { "POST /api/ai/sites domain=${request.domain}" }
    val profile = siteProfiler.profile(request.domain)
    val entry = siteProfileStore.add(request.domain, profile)
    call.respond(HttpStatusCode.Created, entry.toDto())
  }

  delete<Api.Ai.Sites.ByDomain> { resource ->
    log.i { "DELETE /api/ai/sites/${resource.domain}" }
    siteProfileStore.remove(resource.domain)
    call.respond(HttpStatusCode.NoContent)
  }
}

private fun SiteProfileStore.SiteEntry.toDto(): SiteEntry {
  return SiteEntry(
    domain = domain,
    addedAt = addedAt,
    profile = SiteProfileDto(
      robotsTxt = profile.hasRobotsTxt,
      sitemap = profile.sitemaps.firstOrNull(),
      rssFeeds = profile.rssFeeds,
      crawlDelay = profile.crawlDelay,
      lastProfiled = profile.lastProfiled,
    ),
  )
}
