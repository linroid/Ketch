package com.linroid.ketch.app.state

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.util.TaskOrigin
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.discover_failed
import ketch.app.shared.generated.resources.discover_unavailable
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch

/**
 * What adding discovered candidates did.
 *
 * @property added tasks created, in the order of the candidates.
 * @property failed candidates that could not be added, with why.
 */
data class CandidateAddResult(
  val added: List<DownloadTask>,
  val failed: List<Pair<AiCandidate, Throwable>>,
)

/**
 * Runs AI discovery searches, follows the steps the agent reports, and adds the candidates the
 * user picks.
 *
 * A search asked for before discovery is set up waits in [pending] and runs with [runPending]
 * once it is.
 *
 * @param aiSettings supplies the discovery provider.
 * @param scope runs the searches; it should use the main dispatcher.
 */
class AiDiscoverController(
  private val aiSettings: AiSettingsController,
  private val scope: CoroutineScope,
) {
  private val log = KetchLogger("AiDiscover")
  private var job: Job? = null

  /** The query form and selection, kept while the user visits other pages. */
  val draft: AiDiscoverDraft = AiDiscoverDraft()

  /** State of the current search. */
  var state by mutableStateOf<AiDiscoverState>(AiDiscoverState.Idle)
    private set

  /** Steps the agent reported during the current or last search, oldest first. */
  var steps by mutableStateOf<List<DiscoveryStep>>(emptyList())
    private set

  /** A search that waits for discovery to be set up, or `null`. */
  var pending by mutableStateOf<DiscoverRequest?>(null)
    private set

  /**
   * Searches for [query], limited to [sites] (comma or space separated; blank searches the whole
   * web). Cancels a search in progress. While discovery is not set up, the search waits in
   * [pending] instead.
   */
  fun discover(query: String, sites: String) {
    job?.cancel()
    steps = emptyList()
    val siteList = parseSites(sites)
    val provider = aiSettings.provider
    if (provider == null) {
      if (aiSettings.supported) {
        pending = DiscoverRequest(query, siteList).takeIf { query.isNotBlank() }
        state = AiDiscoverState.Idle
      } else {
        state = AiDiscoverState.Error(Res.string.discover_unavailable.text())
      }
      return
    }
    pending = null
    state = AiDiscoverState.Loading
    val request = AiDiscoverRequest(query = query, sites = siteList)
    job = scope.launch {
      // The agent reports steps from its own threads; they reach the state in order, here.
      val reported = Channel<DiscoveryStep>(Channel.UNLIMITED)
      val follower = launch { for (step in reported) steps = steps + step }
      val result = catchingUnlessCancelled {
        try {
          provider.discover(request) { reported.trySend(it) }
        } finally {
          reported.close()
        }
      }
      follower.join()
      result.onSuccess { response ->
        log.i { "Found ${response.candidates.size} candidates in ${steps.size} steps" }
        state = AiDiscoverState.Results(candidates = response.candidates)
      }.onFailure { e ->
        log.w { "Discovery failed: ${e.describeCauses()}" }
        // The engine's message, such as the provider rejecting the token, says what to fix.
        val message = e.message?.let(::verbatim) ?: Res.string.discover_failed.text()
        state = AiDiscoverState.Error(message)
      }
    }
  }

  /** Fills the form from [request] and searches, or keeps it in [pending] until set up. */
  fun discover(request: DiscoverRequest) {
    draft.query = request.query
    draft.sites = request.sites.joinToString(", ")
    draft.showSites = request.sites.isNotEmpty()
    draft.prepareSearch()
    discover(draft.submittedQuery, draft.sites)
  }

  /** Searches the form as it stands. */
  fun search() {
    draft.prepareSearch()
    if (draft.submittedQuery.isEmpty()) return
    discover(draft.submittedQuery, draft.sites)
  }

  /**
   * Runs the search the results are for again, with the websites in the form, even after the
   * search field was changed or cleared.
   */
  fun retry() {
    if (draft.submittedQuery.isEmpty()) return
    discover(draft.submittedQuery, draft.sites)
  }

  /** Runs the [pending] search, once discovery is set up. Returns whether it did. */
  fun runPending(): Boolean {
    val request = pending ?: return false
    if (aiSettings.provider == null) return false
    discover(request)
    return true
  }

  /** Stops the search in progress, keeping the steps it reported. */
  fun stop() {
    if (job?.isActive != true) return
    job?.cancel()
    job = null
    state = AiDiscoverState.Idle
  }

  /** Cancels the search and clears its results and steps. */
  fun reset() {
    job?.cancel()
    job = null
    steps = emptyList()
    state = AiDiscoverState.Idle
  }

  /**
   * Adds each of [candidates] to [api] on its own, so one that fails never stops the others.
   *
   * @param query search that found them, recorded in the request properties.
   */
  suspend fun add(
    api: KetchApi,
    candidates: List<AiCandidate>,
    query: String = draft.submittedQuery,
  ): CandidateAddResult {
    val added = mutableListOf<DownloadTask>()
    val failed = mutableListOf<Pair<AiCandidate, Throwable>>()
    for (candidate in candidates) {
      catchingUnlessCancelled { api.download(candidate.toRequest(query)) }
        .onSuccess { added += it }
        .onFailure { e ->
          log.w { "Couldn't add ${redactUrl(candidate.url)}: ${e.describeCauses()}" }
          failed += candidate to e
        }
    }
    return CandidateAddResult(added, failed)
  }

  /**
   * The add sheet's request for reviewing [candidates] before adding them to [targetDeviceId].
   *
   * @param query search that found them, recorded in the request properties.
   */
  fun reviewRequest(
    candidates: List<AiCandidate>,
    targetDeviceId: String?,
    query: String = draft.submittedQuery,
  ): IntakeRequest = IntakeRequest(
    seeds = candidates.map { it.toSeed(query) },
    targetDeviceId = targetDeviceId,
  )
}

/** Builds the request that adds this candidate, remembering where it came from. */
internal fun AiCandidate.toRequest(query: String): DownloadRequest = DownloadRequest(
  url = url,
  destination = fileName?.takeIf { it.isNotBlank() }?.let(::Destination),
  headers = discoverHeaders(),
  properties = discoverProperties(query),
)

/** The add sheet's row for this candidate, with the same headers and properties. */
internal fun AiCandidate.toSeed(query: String): IntakeSeed = IntakeSeed(
  url = url,
  fileName = fileName?.takeIf { it.isNotBlank() },
  headers = discoverHeaders(),
  properties = discoverProperties(query),
)

// Servers that check the referrer see the page the link was found on, as a browser would send.
private fun AiCandidate.discoverHeaders(): Map<String, String> =
  if (sourceUrl.isNotBlank()) mapOf("Referer" to sourceUrl) else emptyMap()

private fun discoverProperties(query: String): Map<String, String> = buildMap {
  put(TaskOrigin.PROPERTY, TaskOrigin.Discover.id)
  if (query.isNotBlank()) put(QUERY_PROPERTY, query)
}

/** Request property holding the Discover query that found a download. */
internal const val QUERY_PROPERTY = "ketch.query"
