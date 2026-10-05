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
 * @property query what to look for, in the user's words; in a conversation, the latest message.
 * @property sites websites to limit the search to; empty searches the whole web.
 * @property maxResults most candidates to return.
 * @property fileTypes file types to prefer, such as `iso`; empty prefers none.
 * @property history earlier turns of the conversation, oldest first; empty for a first search.
 * @property excludedUrls links the user discarded, which the search never returns.
 * @property contentFilter whether to hide results that look unsafe ([AiSettings.contentFilter]).
 * @property devices the devices the search is for, which the agent may use to pick builds.
 */
data class AiDiscoverRequest(
  val query: String,
  val sites: List<String> = emptyList(),
  val maxResults: Int = 10,
  val fileTypes: List<String> = emptyList(),
  val history: List<AiDiscoverTurn> = emptyList(),
  val excludedUrls: Set<String> = emptySet(),
  val contentFilter: Boolean = true,
  val devices: AiSearchDevices = AiSearchDevices(),
)

/**
 * The devices a search is for, as hints the agent may ignore: it picks builds for their system
 * and CPU when the request names none.
 *
 * @property user the device the user searches from, when known.
 * @property download the device the results are added to, when known; it may be [user].
 */
data class AiSearchDevices(
  val user: AiDevice? = null,
  val download: AiDevice? = null,
)

/**
 * A device as it reports its system.
 *
 * @property os its operating system, such as "Mac OS X" or "Android 15".
 * @property arch its CPU architecture, such as "aarch64"; blank when unknown.
 */
data class AiDevice(
  val os: String,
  val arch: String = "",
)

/**
 * An earlier turn of a discovery conversation, as the agent reads it.
 *
 * @property request what the user asked.
 * @property sites websites that turn was limited to.
 * @property completed whether that turn finished; a failed or stopped turn has no [results].
 * @property results what it found and the user kept, best first.
 */
data class AiDiscoverTurn(
  val request: String,
  val sites: List<String>,
  val completed: Boolean,
  val results: List<AiCandidate>,
)

/**
 * What an AI discovery search found.
 *
 * @property query the search, as the engine read it.
 * @property candidates the downloads it found, best first.
 * @property summary the agent's short reply in plain text; blank when it gave none.
 * @property title the agent's short name for the search, which names a new conversation: one
 *   line of plain text that fetched pages may have shaped, so show it as text only. Blank when
 *   it gave none, as it may for a follow-up.
 * @property filtered how many of the agent's results the content filter hid; [summary] may still
 *   speak of them.
 */
data class AiDiscoverResponse(
  val query: String,
  val candidates: List<AiCandidate>,
  val summary: String = "",
  val title: String = "",
  val filtered: Int = 0,
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

/** What the agent wants to do on a website. */
enum class AiPageKind {
  /** Read a page's text and links. */
  Page,

  /** Check a file's type, size and date. */
  FileInfo,
}

/**
 * A request the agent wants to make to a website, which waits for the user's answer unless the
 * page access settings answer it.
 *
 * @property url the URL to request.
 * @property host the host of [url], lowercase.
 * @property kind what the request is for.
 * @property reason why the agent says it needs it, one line; text that fetched pages may have
 *   shaped, so show it as the agent's words only. Blank when it gave none.
 * @property redirectFrom the host that redirected here; blank for a request the agent asked for.
 */
data class AiPageRequest(
  val url: String,
  val host: String,
  val kind: AiPageKind,
  val reason: String = "",
  val redirectFrom: String = "",
)

/**
 * A search that failed.
 *
 * @property brief [message] without the reason the model provider gave, which may echo a token:
 *   what is safe to keep, such as in the saved history.
 */
class AiDiscoverFailure(
  message: String,
  val brief: String,
  cause: Throwable? = null,
) : Exception(message, cause)

/**
 * Abstraction for AI resource discovery, allowing platform-specific
 * implementations (e.g., embedded in-process on JVM/Android).
 *
 * Searches may run at the same time; each reports to its own callbacks.
 */
interface AiDiscoveryProvider {
  /**
   * Searches for downloads matching [request].
   *
   * @param onStep called with each step the agent reports while it works, possibly from another
   *   thread.
   * @param approve asked before the agent contacts a website, possibly from another thread; it
   *   may suspend until the user answers, and `false` declines the request.
   * @throws AiDiscoverFailure when the model provider fails or the agent gives no answer.
   */
  suspend fun discover(
    request: AiDiscoverRequest,
    onStep: (DiscoveryStep) -> Unit,
    approve: suspend (AiPageRequest) -> Boolean,
  ): AiDiscoverResponse

  /**
   * Sends a minimal prompt to the configured provider to check the
   * endpoint, model and credentials.
   *
   * @return the model's reply text
   */
  suspend fun verify(): String

  /** Releases any resources the implementation holds, once the searches running now end. */
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
