package com.linroid.ketch.app.state

/**
 * A discovered resource candidate from AI discovery.
 */
data class AiCandidate(
  val url: String,
  val title: String,
  val fileName: String? = null,
  val fileSize: Long? = null,
  val mimeType: String? = null,
  val sourceUrl: String = "",
  val confidence: Float,
  val description: String,
)

/**
 * Request for AI resource discovery.
 */
data class AiDiscoverRequest(
  val query: String,
  val sites: List<String> = emptyList(),
  val maxResults: Int = 10,
  val fileTypes: List<String> = emptyList(),
)

/**
 * Response from AI resource discovery.
 */
data class AiDiscoverResponse(
  val query: String,
  val candidates: List<AiCandidate>,
)

/**
 * Abstraction for AI resource discovery, allowing platform-specific
 * implementations (e.g., embedded in-process on JVM/Android).
 */
interface AiDiscoveryProvider {
  suspend fun discover(request: AiDiscoverRequest): AiDiscoverResponse
}
