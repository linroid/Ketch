package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/**
 * The announces of a private hybrid, whose v1 and v2 hashes are two tracker topics (BEP 27: one
 * tracker at a time, and only that tracker's peers). Every topic announces to the same tracker,
 * taken from [urls] in order, and the group stays with it while it answers any topic it answered
 * before, so a tracker that knows only one of the hashes keeps both. Once it fails a topic it
 * answered, or answers none, every topic moves to the next tracker together: [onSwitch] first
 * drops the peers the old tracker gave (only if it gave any), then each topic stops at the old
 * tracker and starts at once at the new one, whatever its own interval.
 *
 * A tracker's announces are [discovery] (topic, url)'s, made anew for each tracker. One caller at
 * a time; each [poll] announces the topics that are due concurrently.
 */
internal class PrivateTrackerGroup(
  private val topics: List<TrackerTopic>,
  private val urls: List<String>,
  private val discovery: (TrackerTopic, String) -> TrackerDiscovery,
  private val onSwitch: suspend () -> Unit,
  private val nowMs: () -> Long = monotonicClock(),
) {
  private val log = KetchLogger("TorrentTracker")
  private var index = 0
  private var discoveries = topics.map { discovery(it, urls.first()) }
  // Per topic, at the current tracker: it answered at least once, and its last attempt failed.
  private val answered = BooleanArray(topics.size)
  private val failing = BooleanArray(topics.size)
  // Trackers left in a row without any answer; a whole round of them backs off.
  private var silent = 0
  private var retryAt = 0L
  private var retryDelayMs = INITIAL_RETRY_MS

  init { require(topics.isNotEmpty() && urls.isNotEmpty()) }

  /** The tracker every topic announces to now. */
  val current: String get() = urls[index]

  /**
   * Announces every topic that is due to the current tracker and returns the answers, then moves
   * to the next tracker if this one no longer serves. Failed announces are not thrown: they are
   * what decides a move. A round that moves returns nothing, even what other topics answered:
   * those peers belong to the tracker the group just left.
   */
  suspend fun poll(
    verified: BooleanArray,
    downloaded: Long,
    uploaded: Long,
  ): List<Pair<TrackerTopic, TrackerResponse>> {
    if (nowMs() < retryAt) return emptyList()
    val outcomes = coroutineScope {
      discoveries.map { discovery ->
        async {
          try {
            Result.success(discovery.poll(verified, downloaded, uploaded))
          } catch (error: CancellationException) {
            throw error
          } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            Result.failure(error)
          }
        }
      }.awaitAll()
    }
    val found = mutableListOf<Pair<TrackerTopic, TrackerResponse>>()
    for ((topic, outcome) in outcomes.withIndex()) {
      val response = outcome.getOrNull()
      if (outcome.isFailure) failing[topic] = true
      else if (response != null) {
        answered[topic] = true
        failing[topic] = false
        found += topics[topic] to response
      }
    }
    if (found.isNotEmpty()) {
      silent = 0
      retryDelayMs = INITIAL_RETRY_MS
    }
    val failed = topics.indices.any { answered[it] && failing[it] } || failing.all { it }
    if (failed && urls.size > 1) {
      move(verified, downloaded, uploaded)
      // This round's answers came from the tracker whose peers the move just dropped.
      return emptyList()
    }
    return found
  }

  /** Tells the current tracker each topic stopped (BEP 3 `stopped`), after a last due announce. */
  suspend fun stop(verified: BooleanArray, downloaded: Long, uploaded: Long) = coroutineScope {
    for (discovery in discoveries) launch {
      attempt { discovery.poll(verified, downloaded, uploaded) }
      attempt { discovery.poll(verified, downloaded, uploaded, stopped = true) }
    }
  }

  private suspend fun move(verified: BooleanArray, downloaded: Long, uploaded: Long) {
    val left = discoveries
    val gavePeers = answered.any { it }
    // Peers of one private tracker never meet another's: drop them before announcing anew.
    if (gavePeers) onSwitch()
    // The tracker is most likely down: telling it we left must not hold up the next one.
    coroutineScope {
      for (discovery in left) launch {
        withTimeoutOrNull(STOP_TIMEOUT_MS) {
          attempt { discovery.poll(verified, downloaded, uploaded, stopped = true) }
        }
      }
    }
    index = (index + 1) % urls.size
    discoveries = topics.map { discovery(it, urls[index]) }
    answered.fill(false)
    failing.fill(false)
    log.d { "Private trackers of ${topics.first().logHash()} moved to ${trackerLabel(current)}" }
    if (gavePeers) silent = 0
    else if (++silent >= urls.size) {
      // No tracker answered a whole round: wait before the next one, as one tracker would.
      silent = 0
      retryAt = nowMs() + retryDelayMs
      retryDelayMs = minOf(MAX_RETRY_MS, retryDelayMs * 2)
    }
  }

  private suspend fun attempt(block: suspend () -> Unit) {
    try {
      block()
    } catch (error: CancellationException) {
      throw error
    } catch (_: Exception) {
      currentCoroutineContext().ensureActive()
    }
  }

  companion object {
    private const val INITIAL_RETRY_MS = 15_000L
    private const val MAX_RETRY_MS = 900_000L
    private const val STOP_TIMEOUT_MS = 2_000L

    /** Every tier's trackers in a random order, tier by tier, each once. */
    fun order(tiers: List<List<String>>, random: Random = Random): List<String> =
      tiers.flatMap { it.distinct().shuffled(random) }.distinct()
  }
}
