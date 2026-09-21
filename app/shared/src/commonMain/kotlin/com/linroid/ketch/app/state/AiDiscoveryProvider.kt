package com.linroid.ketch.app.state

import com.linroid.ketch.config.AiSettings

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

  /**
   * Sends a minimal prompt to the configured provider to check the
   * endpoint, model and credentials.
   *
   * @return the model's reply text
   */
  suspend fun verify(): String

  /** Releases any resources the implementation holds. */
  fun close() {}
}

/**
 * Builds an [AiDiscoveryProvider] for the given settings.
 *
 * Platforms that cannot run the discovery engine (web, iOS) supply no
 * factory at all, which is what makes the settings page report AI
 * discovery as unsupported.
 */
fun interface AiDiscoveryProviderFactory {
  /**
   * Creates a provider, or returns `null` when [settings] (plus any
   * credentials picked up from the environment) cannot drive discovery.
   */
  fun create(settings: AiSettings): AiDiscoveryProvider?

  /**
   * Returns [settings] with the blank credentials this platform can
   * supply filled in — e.g. an API key from the environment — without
   * switching providers or turning the feature on. Lets the settings
   * page judge an unsaved form the same way [create] will.
   */
  fun withPlatformCredentials(settings: AiSettings): AiSettings = settings
}
