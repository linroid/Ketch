package com.linroid.ketch.app.state

import com.linroid.ketch.config.AiSettings

/**
 * A download AI discovery found.
 *
 * @property url link to download.
 * @property title what the agent calls it.
 * @property fileName name of the file, when the link names one.
 * @property fileSize size in bytes, when the server reported it.
 * @property mimeType content type, when the server reported it.
 * @property sourceUrl page the link was found on; blank when unknown.
 * @property confidence how sure the agent is that it matches the search, from 0 to 1.
 * @property description what the agent says the file is.
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
 * A search for AI discovery.
 *
 * @property query what to look for, in the user's words.
 * @property sites websites to limit the search to; empty searches the whole web.
 * @property maxResults most candidates to return.
 * @property fileTypes file types to prefer, such as `iso`; empty prefers none.
 */
data class AiDiscoverRequest(
  val query: String,
  val sites: List<String> = emptyList(),
  val maxResults: Int = 10,
  val fileTypes: List<String> = emptyList(),
)

/**
 * What an AI discovery search found.
 *
 * @property query the search, as the engine read it.
 * @property candidates the downloads it found, best first.
 */
data class AiDiscoverResponse(
  val query: String,
  val candidates: List<AiCandidate>,
)

/**
 * Something the discovery agent reported doing, such as "Plan" or "Searching".
 *
 * @property title short name of the step.
 * @property detail what the agent said about it; may be blank.
 */
data class DiscoveryStep(
  val title: String,
  val detail: String = "",
)

/**
 * Abstraction for AI resource discovery, allowing platform-specific
 * implementations (e.g., embedded in-process on JVM/Android).
 */
interface AiDiscoveryProvider {
  /**
   * Searches for downloads matching [request].
   *
   * @param onStep called with each step the agent reports while it works, possibly from another
   *   thread.
   */
  suspend fun discover(
    request: AiDiscoverRequest,
    onStep: (DiscoveryStep) -> Unit = {},
  ): AiDiscoverResponse

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
