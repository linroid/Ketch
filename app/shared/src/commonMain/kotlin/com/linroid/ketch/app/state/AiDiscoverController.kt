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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
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
 * Runs AI discovery searches and adds the candidates the user picks.
 *
 * @param aiSettings supplies the discovery provider.
 * @param scope runs the searches.
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

  /**
   * Searches for [query], limited to [sites] (comma or space separated; blank searches the whole
   * web). Cancels a search in progress.
   */
  fun discover(query: String, sites: String) {
    job?.cancel()
    val provider = aiSettings.provider
    if (provider == null) {
      state = AiDiscoverState.Error(
        if (aiSettings.supported) {
          "Add an AI provider and API token in Settings first."
        } else {
          "AI discovery is not available on this platform."
        },
      )
      return
    }
    state = AiDiscoverState.Loading
    val siteList = sites.split(",", " ").map { it.trim() }.filter { it.isNotBlank() }
    job = scope.launch {
      try {
        val response = provider.discover(AiDiscoverRequest(query = query, sites = siteList))
        state = AiDiscoverState.Results(candidates = response.candidates)
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Discovery failed: ${e.describeCauses()}" }
        state = AiDiscoverState.Error(e.message ?: "Discovery failed")
      }
    }
  }

  /** Fills the form from [request] and searches. */
  fun discover(request: DiscoverRequest) {
    draft.query = request.query
    draft.sites = request.sites.joinToString(", ")
    draft.showSites = request.sites.isNotEmpty()
    draft.prepareSearch()
    discover(draft.submittedQuery, draft.sites)
  }

  /** Cancels the search and clears its results. */
  fun reset() {
    job?.cancel()
    job = null
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
      try {
        added += api.download(candidate.toRequest(query))
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Couldn't add ${redactUrl(candidate.url)}: ${e.describeCauses()}" }
        failed += candidate to e
      }
    }
    return CandidateAddResult(added, failed)
  }
}

/** Builds the request that adds this candidate, remembering where it came from. */
internal fun AiCandidate.toRequest(query: String): DownloadRequest = DownloadRequest(
  url = url,
  destination = fileName?.takeIf { it.isNotBlank() }?.let(::Destination),
  headers = if (sourceUrl.isNotBlank()) mapOf("Referer" to sourceUrl) else emptyMap(),
  properties = buildMap {
    put(ORIGIN_PROPERTY, "discover")
    if (query.isNotBlank()) put(QUERY_PROPERTY, query)
  },
)

/** Request property naming the surface a download was added from. */
internal const val ORIGIN_PROPERTY = "ketch.origin"

/** Request property holding the Discover query that found a download. */
internal const val QUERY_PROPERTY = "ketch.query"
