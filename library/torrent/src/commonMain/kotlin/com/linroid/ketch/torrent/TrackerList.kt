package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.IO
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString.Companion.toByteString
import okio.Path
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.time.Clock
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

/**
 * Trackers of one of the lists [TorrentConfig.trackerListUrls] names.
 *
 * @property url the subscribed list, or `null` when there is none.
 * @property trackers the usable announce URLs of the newest copy of the list, in its order.
 * @property updatedAt when that copy was downloaded; `null` before the first download.
 * @property failed whether the last download failed; [trackers] then come from an older copy.
 * @property updating whether the list is being downloaded.
 */
data class TrackerListState(
  val url: String? = null,
  val trackers: List<String> = emptyList(),
  val updatedAt: Instant? = null,
  val failed: Boolean = false,
  val updating: Boolean = false,
)

/**
 * Downloads the tracker list at a URL when subscribed and again every [refreshInterval], keeping
 * the newest copy in [cacheFile] so a restart uses it without downloading. A failed download
 * keeps the copy it has and tries again after [retryInterval]. Until the first download, a list
 * Ketch ships a copy of ([bundled]) uses that copy.
 */
internal class TrackerListSubscription(
  private val http: () -> TorrentHttp,
  private val cacheFile: Path?,
  private val scope: CoroutineScope,
  private val onTrackers: suspend (List<String>) -> Unit,
  private val bundled: (url: String) -> List<String> = ::bundledTrackers,
  private val clock: Clock = Clock.System,
  private val refreshInterval: Duration = 1.days,
  private val retryInterval: Duration = 1.hours,
) {
  private val log = KetchLogger("TrackerList")
  private val mutableState = MutableStateFlow(TrackerListState())
  private val refreshRequests = Channel<Unit>(Channel.CONFLATED)
  private val mutex = Mutex()
  private var job: Job? = null

  val state: StateFlow<TrackerListState> = mutableState.asStateFlow()

  /**
   * Subscribes to the list at [url], or unsubscribes for `null` or a URL that is not `http` or
   * `https`. Returns once the trackers it has without downloading, the saved copy or else the
   * shipped one, are passed to `onTrackers`; downloads run afterwards.
   */
  suspend fun subscribe(requested: String?): Unit = mutex.withLock {
    val url = requested?.takeIf(::isTrackerListUrl)
    if (requested != null && url == null) log.w { "Ignoring a tracker list URL not http(s)" }
    if (url == mutableState.value.url) return@withLock
    job?.cancelAndJoin()
    job = null
    if (url == null) {
      mutableState.value = TrackerListState()
      onTrackers(emptyList())
      log.i { "Tracker list unsubscribed" }
      return@withLock
    }
    val cached = readCache(url)
    val trackers = cached?.trackers ?: bundled(url)
    mutableState.value = TrackerListState(url, trackers, cached?.updatedAt)
    onTrackers(trackers)
    if (cached != null) log.d { "Tracker list restored: ${trackers.size} tracker(s)" }
    refreshRequests.tryReceive()
    job = scope.launch { run(url, cached?.updatedAt) }
  }

  /** Downloads the subscribed list now instead of waiting for its next refresh. */
  fun refresh() {
    refreshRequests.trySend(Unit)
  }

  /** Downloads the list at [url] when the copy from [savedAt] is a day old, then daily. */
  private suspend fun run(url: String, savedAt: Instant?) {
    var next = savedAt?.let { it + refreshInterval } ?: clock.now()
    while (true) {
      val wait = next - clock.now()
      if (wait.isPositive()) withTimeoutOrNull(wait) { refreshRequests.receive() }
      mutableState.update { it.copy(updating = true) }
      next = try {
        val trackers = download(url)
        val now = clock.now()
        writeCache(url, trackers, now)
        mutableState.update {
          it.copy(trackers = trackers, updatedAt = now, failed = false, updating = false)
        }
        onTrackers(trackers)
        log.i { "Tracker list updated: ${trackers.size} tracker(s)" }
        now + refreshInterval
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.w { "Tracker list download failed: ${e.describeWithoutUrls()}" }
        mutableState.update { it.copy(failed = true, updating = false) }
        clock.now() + retryInterval
      }
    }
  }

  /**
   * The usable trackers of the list at [url], from its jsDelivr mirror when GitHub cannot be
   * reached or answers with no trackers.
   */
  private suspend fun download(url: String): List<String> {
    val failure = try {
      return fetchTrackers(url)
    } catch (e: CancellationException) {
      throw e
    } catch (e: Exception) {
      e
    }
    val mirror = trackerListMirror(url) ?: throw failure
    log.d { "Tracker list download failed, trying its mirror: ${failure.describeWithoutUrls()}" }
    return fetchTrackers(mirror)
  }

  private suspend fun fetchTrackers(url: String): List<String> {
    val trackers = parseTrackerList(http().fetch(url, MAX_TRACKER_LIST_BYTES).decodeToString())
    check(trackers.isNotEmpty()) { "Tracker list has no usable trackers" }
    return trackers
  }

  private class Cached(val trackers: List<String>, val updatedAt: Instant)

  private suspend fun readCache(url: String): Cached? {
    val file = cacheFile ?: return null
    return withContext(Dispatchers.IO) {
      try {
        if (!torrentSystemFileSystem.exists(file)) return@withContext null
        val size = torrentSystemFileSystem.metadata(file).size ?: Long.MAX_VALUE
        if (size > MAX_TRACKER_LIST_BYTES * 2L) return@withContext null
        val lines = torrentSystemFileSystem.read(file) { readUtf8() }.lines()
        // A copy of another list, or one without a time, is downloaded again.
        if (lines.getOrNull(1) != "$CACHE_URL$url") return@withContext null
        val millis = lines.getOrNull(2)?.removePrefix(CACHE_UPDATED)?.toLongOrNull()
          ?: return@withContext null
        Cached(parseTrackerList(lines.drop(3).joinToString("\n")),
          Instant.fromEpochMilliseconds(millis))
      } catch (e: Exception) {
        log.d { "Tracker list copy could not be read: ${e.describeWithoutUrls()}" }
        null
      }
    }
  }

  private suspend fun writeCache(url: String, trackers: List<String>, updatedAt: Instant) {
    val file = cacheFile ?: return
    val parent = file.parent ?: return
    withContext(Dispatchers.IO) {
      val temporary = parent / "${file.name}.${torrentRandomBytes(4).toByteString().hex()}.tmp"
      try {
        torrentSystemFileSystem.createDirectories(parent)
        torrentSystemFileSystem.write(temporary) {
          writeUtf8("$CACHE_HEADER\n$CACHE_URL$url\n")
          writeUtf8("$CACHE_UPDATED${updatedAt.toEpochMilliseconds()}\n")
          trackers.forEach { writeUtf8("$it\n") }
        }
        torrentSystemFileSystem.atomicMove(temporary, file)
      } catch (e: Exception) {
        log.w { "Tracker list copy could not be saved: ${e.describeWithoutUrls()}" }
        try {
          torrentSystemFileSystem.delete(temporary, mustExist = false)
        } catch (_: Exception) {
        }
      }
    }
  }

  companion object {
    /** Largest list accepted; ngosang's `trackers_all.txt` is about 4 KiB. */
    const val MAX_TRACKER_LIST_BYTES = 256 * 1024

    private const val CACHE_HEADER = "# Ketch tracker list copy"
    private const val CACHE_URL = "# url "
    private const val CACHE_UPDATED = "# updated "
  }
}

/**
 * Several tracker lists, each a [TrackerListSubscription] with its own copy in [stateDirectory],
 * whose trackers [onTrackers] gets combined in list order, without repeats.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TrackerLists(
  private val http: () -> TorrentHttp,
  private val stateDirectory: Path?,
  private val scope: CoroutineScope,
  private val onTrackers: suspend (List<String>) -> Unit,
  private val bundled: (url: String) -> List<String> = ::bundledTrackers,
  private val clock: Clock = Clock.System,
) {
  private val log = KetchLogger("TrackerList")
  private val mutex = Mutex()
  private val subscribed = MutableStateFlow(emptyList<Pair<String, TrackerListSubscription>>())
  // The lists [publish] combines. It is set before new lists subscribe, because each publishes
  // as it subscribes, and [subscribed] still holds the previous lists then (none at first).
  private val publishing = AtomicReference(emptyList<TrackerListSubscription>())

  /** Each subscribed list, in order. */
  @OptIn(ExperimentalCoroutinesApi::class)
  val state: StateFlow<List<TrackerListState>> = subscribed
    .flatMapLatest { lists ->
      if (lists.isEmpty()) flowOf(emptyList()) else combine(lists.map { it.second.state }) {
        it.toList()
      }
    }
    .stateIn(scope, SharingStarted.Eagerly, emptyList())

  /**
   * Subscribes to the lists at [requested], in that order, keeping the ones already subscribed;
   * URLs that are not `http` or `https` are skipped. Returns once every list's saved or shipped
   * copy is passed to `onTrackers`.
   */
  suspend fun subscribe(requested: List<String>): Unit = mutex.withLock {
    val urls = requested.map { it.trim() }.filter(::isTrackerListUrl).distinct()
    if (urls.size < requested.size) log.d { "Ignoring ${requested.size - urls.size} list URL(s)" }
    val current = subscribed.value
    if (urls == current.map { it.first }) return@withLock
    val kept = current.toMap()
    current.filter { it.first !in urls }.forEach { it.second.subscribe(null) }
    val next = urls.map { url -> url to (kept[url] ?: newList(url)) }
    publishing.store(next.map { it.second })
    // Subscribed before they are published, so [state] never shows a list without its URL.
    next.forEach { (url, list) -> list.subscribe(url) }
    subscribed.value = next
    publish()
    log.i { "Tracker lists: ${urls.size}" }
  }

  /** Downloads every subscribed list now. */
  fun refresh() {
    subscribed.value.forEach { it.second.refresh() }
  }

  // Each list keeps its own copy, named after its URL.
  private fun newList(url: String): TrackerListSubscription {
    val hash = sha1Digest(url.encodeToByteArray()).toByteString().hex().take(12)
    return TrackerListSubscription(http, stateDirectory?.resolve("tracker-list-$hash.txt"), scope,
      onTrackers = { publish() }, bundled = bundled, clock = clock)
  }

  private suspend fun publish() {
    onTrackers(publishing.load().flatMap { it.state.value.trackers }.distinct())
  }
}

/**
 * The jsDelivr mirror of a `raw.githubusercontent.com` [url], which some networks reach when
 * they cannot reach GitHub; `null` for other URLs.
 */
internal fun trackerListMirror(url: String): String? {
  val prefix = "https://raw.githubusercontent.com/"
  if (!url.startsWith(prefix)) return null
  val parts = url.removePrefix(prefix).split('/')
  if (parts.size < 4 || parts[2] == "refs" || parts.any { it.isEmpty() }) return null
  val (owner, repo, ref) = parts
  return "https://cdn.jsdelivr.net/gh/$owner/$repo@$ref/${parts.drop(3).joinToString("/")}"
}

/** The copy of the list at [url] that Ketch ships, if any: [TorrentConfig.BEST_TRACKERS]. */
internal fun bundledTrackers(url: String): List<String> =
  if (url == TorrentConfig.BEST_TRACKERS_URL) TorrentConfig.BEST_TRACKERS else emptyList()

/** Whether [url] can name a tracker list: `http` or `https` with a host. */
internal fun isTrackerListUrl(url: String): Boolean {
  val scheme = url.substringBefore("://", "").lowercase()
  val host = url.substringAfter("://").takeWhile { it != '/' && it != '?' && it != '#' }
  return (scheme == "http" || scheme == "https") && host.isNotEmpty() && url.none { it <= ' ' }
}

/**
 * Announce URLs in a tracker list: one per line, blank lines and `#` comments skipped. URLs the
 * engine cannot announce to, such as WebTorrent's `wss`, are dropped; at most 128 are kept.
 */
internal fun parseTrackerList(text: String): List<String> = text.lineSequence()
  .map { it.trim() }
  .filter { it.isNotEmpty() && !it.startsWith('#') }
  .filter { url ->
    try {
      TrackerConfiguration.prepare(listOf(listOf(url)))
      true
    } catch (_: IllegalArgumentException) {
      false
    }
  }
  .distinct()
  .take(MAX_ADDITIONAL_TRACKERS)
  .toList()
