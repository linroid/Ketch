package com.linroid.ketch.remote

import com.linroid.ketch.endpoints.Api
import com.linroid.ketch.endpoints.model.AddSiteRequest
import com.linroid.ketch.endpoints.model.AiDownloadRequest
import com.linroid.ketch.endpoints.model.AiDownloadResponse
import com.linroid.ketch.endpoints.model.DiscoverRequest
import com.linroid.ketch.endpoints.model.DiscoverResponse
import com.linroid.ketch.endpoints.model.SiteEntry
import com.linroid.ketch.endpoints.model.SitesResponse
import io.ktor.client.call.body
import io.ktor.client.plugins.resources.delete
import io.ktor.client.plugins.resources.get
import io.ktor.client.plugins.resources.post
import io.ktor.client.request.setBody
import io.ktor.http.ContentType
import io.ktor.http.contentType

/**
 * Discovers downloadable resources using AI analysis.
 *
 * This is an extension function on [RemoteKetch] because AI
 * discovery is an optional feature, not part of the core
 * [KetchApi][com.linroid.ketch.api.KetchApi] contract.
 *
 * @param request discovery parameters (query, sites, etc.)
 * @return discovery results with ranked candidates
 */
suspend fun RemoteKetch.discoverResources(
  request: DiscoverRequest,
): DiscoverResponse {
  val response = httpClient.post(Api.Ai.Discover()) {
    contentType(ContentType.Application.Json)
    setBody(request)
  }
  checkSuccess(response)
  return response.body()
}

/**
 * Starts downloads for selected AI discovery candidates.
 *
 * @param request download parameters with candidate URLs
 * @return results with task IDs for each download
 */
suspend fun RemoteKetch.downloadCandidates(
  request: AiDownloadRequest,
): AiDownloadResponse {
  val response = httpClient.post(Api.Ai.Download()) {
    contentType(ContentType.Application.Json)
    setBody(request)
  }
  checkSuccess(response)
  return response.body()
}

/**
 * Lists all allowlisted sites with their profiles.
 */
suspend fun RemoteKetch.getSites(): SitesResponse {
  val response = httpClient.get(Api.Ai.Sites())
  checkSuccess(response)
  return response.body()
}

/**
 * Adds a site to the allowlist and triggers profiling.
 *
 * @param request the domain to add
 * @return the created site entry with profile
 */
suspend fun RemoteKetch.addSite(
  request: AddSiteRequest,
): SiteEntry {
  val response = httpClient.post(Api.Ai.Sites()) {
    contentType(ContentType.Application.Json)
    setBody(request)
  }
  checkSuccess(response)
  return response.body()
}

/**
 * Removes a site from the allowlist.
 *
 * @param domain the domain to remove
 */
suspend fun RemoteKetch.removeSite(domain: String) {
  val response = httpClient.delete(
    Api.Ai.Sites.ByDomain(domain = domain),
  )
  checkSuccess(response)
}
