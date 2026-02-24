package com.linroid.ketch.endpoints.model

import kotlinx.serialization.Serializable
import kotlin.time.Instant

// -- Request models --

/**
 * Request body for `POST /api/ai/discover`.
 */
@Serializable
data class DiscoverRequest(
  val query: String,
  val sites: List<String> = emptyList(),
  val maxResults: Int = 10,
  val fileTypes: List<String> = emptyList(),
)

/**
 * A candidate URL to download, sent as part of [AiDownloadRequest].
 */
@Serializable
data class AiDownloadCandidate(
  val url: String,
  val fileName: String? = null,
)

/**
 * Request body for `POST /api/ai/download`.
 */
@Serializable
data class AiDownloadRequest(
  val candidates: List<AiDownloadCandidate>,
  val destination: String? = null,
  val connections: Int = 0,
)

/**
 * Request body for `POST /api/ai/sites`.
 */
@Serializable
data class AddSiteRequest(
  val domain: String,
)

// -- Response models --

/**
 * A discovered downloadable resource candidate.
 */
@Serializable
data class ResourceCandidate(
  val url: String,
  val title: String,
  val fileName: String? = null,
  val fileSize: Long? = null,
  val mimeType: String? = null,
  val sourceUrl: String,
  val confidence: Float,
  val description: String = "",
)

/**
 * Reference to a source page that was fetched during discovery.
 */
@Serializable
data class SourceReference(
  val url: String,
  val title: String,
  val fetchedAt: Instant,
)

/**
 * Response body for `POST /api/ai/discover`.
 */
@Serializable
data class DiscoverResponse(
  val query: String,
  val candidates: List<ResourceCandidate>,
  val sources: List<SourceReference>,
)

/**
 * Result of a single download initiated by the AI download endpoint.
 */
@Serializable
data class AiDownloadResult(
  val url: String,
  val taskId: String,
  val status: String,
)

/**
 * Response body for `POST /api/ai/download`.
 */
@Serializable
data class AiDownloadResponse(
  val downloads: List<AiDownloadResult>,
)

/**
 * Profile information for an allowlisted site.
 */
@Serializable
data class SiteProfileDto(
  val robotsTxt: Boolean = false,
  val sitemap: String? = null,
  val rssFeeds: List<String> = emptyList(),
  val crawlDelay: Int? = null,
  val lastProfiled: Instant? = null,
)

/**
 * An entry in the site allowlist.
 */
@Serializable
data class SiteEntry(
  val domain: String,
  val addedAt: Instant,
  val profile: SiteProfileDto? = null,
)

/**
 * Response body for `GET /api/ai/sites`.
 */
@Serializable
data class SitesResponse(
  val sites: List<SiteEntry>,
)
