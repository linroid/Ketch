package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Clock
import kotlin.time.Duration.Companion.hours
import kotlin.time.Instant

class TrackerListTest {
  private val directory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-tracker-list-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
  private val cacheFile: Path = directory / "tracker-list.txt"
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val clock = object : Clock {
    var now = Instant.fromEpochMilliseconds(1_790_000_000_000)
    override fun now(): Instant = now
  }
  private var body: String? = "$UDP\n$HTTP\n"
  // Bodies by URL, for lists that differ; others get [body].
  private val bodies = mutableMapOf<String, String?>()
  private val requests = mutableListOf<String>()
  private val published = mutableListOf<List<String>>()

  @AfterTest
  fun cleanUp() {
    scope.cancel()
    FileSystem.SYSTEM.deleteRecursively(directory, mustExist = false)
  }

  @Test
  fun parseTrackerList_skipsCommentsBlanksRepeatsAndUnusableSchemes() {
    val text = "# best\n\n $UDP \n$UDP\nwss://tracker.webtorrent.dev:443\n" +
      "udp://no-port/announce\n$HTTP\n"
    assertEquals(listOf(UDP, HTTP), parseTrackerList(text))
  }

  @Test
  fun parseTrackerList_keepsAtMost64() {
    val text = (1..100).joinToString("\n") { "udp://tracker$it.example:1337/announce" }
    val parsed = parseTrackerList(text)
    assertEquals(64, parsed.size)
    assertEquals("udp://tracker1.example:1337/announce", parsed.first())
  }

  @Test
  fun subscribe_ignoresNonHttpUrl() = runTest {
    val subscription = subscription()
    subscription.subscribe("ftp://example.org/trackers.txt")
    assertEquals(TrackerListState(), subscription.state.value)
    assertEquals(emptyList(), requests)
  }

  @Test
  fun subscribe_downloadsPublishesAndSavesTheList() = realTime {
    val subscription = subscription()
    subscription.subscribe(LIST)
    val state = subscription.state.first { it.updatedAt != null }
    assertEquals(listOf(UDP, HTTP), state.trackers)
    assertEquals(clock.now, state.updatedAt)
    assertEquals(listOf(LIST), requests)
    assertEquals(listOf(UDP, HTTP), published.last())
    assertTrue(FileSystem.SYSTEM.exists(cacheFile))
  }

  @Test
  fun subscribe_usesAFreshCopyWithoutDownloading() = realTime {
    subscription().also { it.subscribe(LIST) }.state.first { it.updatedAt != null }
    val saved = clock.now
    requests.clear()
    clock.now = saved + 23.hours

    val restarted = subscription()
    restarted.subscribe(LIST)
    val state = restarted.state.first { it.trackers.isNotEmpty() }
    assertEquals(listOf(UDP, HTTP), state.trackers)
    assertEquals(saved, state.updatedAt)
    assertEquals(emptyList(), requests)
  }

  @Test
  fun subscribe_downloadsAgainWhenTheCopyIsADayOld() = realTime {
    subscription().also { it.subscribe(LIST) }.state.first { it.updatedAt != null }
    requests.clear()
    clock.now += 25.hours
    body = "$HTTP\n"

    val restarted = subscription()
    restarted.subscribe(LIST)
    assertEquals(listOf(HTTP), restarted.state.first { it.updatedAt == clock.now }.trackers)
    assertEquals(listOf(LIST), requests)
  }

  @Test
  fun subscribe_ignoresTheCopyOfAnotherList() = realTime {
    subscription().also { it.subscribe(LIST) }.state.first { it.updatedAt != null }
    requests.clear()

    val other = subscription()
    other.subscribe(OTHER_LIST)
    other.state.first { it.updatedAt != null }
    assertEquals(listOf(OTHER_LIST), requests)
  }

  @Test
  fun failedDownload_keepsTheOlderCopy() = realTime {
    subscription().also { it.subscribe(LIST) }.state.first { it.updatedAt != null }
    val saved = clock.now
    clock.now += 25.hours
    body = null

    val restarted = subscription()
    restarted.subscribe(LIST)
    val state = restarted.state.first { it.failed }
    assertEquals(listOf(UDP, HTTP), state.trackers)
    assertEquals(saved, state.updatedAt)
    assertEquals(listOf(UDP, HTTP), published.last())
  }

  @Test
  fun listWithoutUsableTrackers_countsAsAFailure() = realTime {
    body = "<html>Sign in to this network</html>"
    val subscription = subscription()
    subscription.subscribe(LIST)
    val state = subscription.state.first { it.failed }
    assertEquals(emptyList(), state.trackers)
    assertNull(state.updatedAt)
  }

  @Test
  fun refresh_downloadsBeforeTheDailyRefresh() = realTime {
    val subscription = subscription()
    subscription.subscribe(LIST)
    subscription.state.first { it.updatedAt != null }
    body = "$HTTP\n"
    clock.now += 1.hours
    subscription.refresh()
    assertEquals(listOf(HTTP), subscription.state.first { it.updatedAt == clock.now }.trackers)
    assertEquals(listOf(LIST, LIST), requests)
  }

  @Test
  fun bundledCopy_isUsedUntilTheFirstDownloadSucceeds() = realTime {
    body = null
    val subscription = subscription()
    subscription.subscribe(BUNDLED_LIST)
    val state = subscription.state.first { it.failed }
    assertEquals(listOf(BUNDLED), state.trackers)
    assertNull(state.updatedAt)
    assertEquals(listOf(BUNDLED), published.last())

    body = "$UDP\n"
    subscription.refresh()
    assertEquals(listOf(UDP), subscription.state.first { it.updatedAt != null }.trackers)
  }

  @Test
  fun subscribe_returnsWithTheSavedCopyPublished() = realTime {
    subscription().also { it.subscribe(LIST) }.state.first { it.updatedAt != null }
    published.clear()
    body = null

    val restarted = subscription()
    restarted.subscribe(LIST)
    assertEquals(listOf(UDP, HTTP), restarted.state.value.trackers)
    assertEquals(listOf(UDP, HTTP), published.single())
  }

  @Test
  fun subscribe_returnsWithTheShippedCopyPublished() = realTime {
    body = null
    val subscription = subscription()
    subscription.subscribe(BUNDLED_LIST)
    assertEquals(listOf(BUNDLED), subscription.state.value.trackers)
    assertEquals(listOf(BUNDLED), published.single())
  }

  @Test
  fun newSource_hasTheShippedTrackersBeforeAnyDownload() {
    body = null
    val source = TorrentDownloadSource(
      TorrentConfig(dhtEnabled = false, trackerListUrls = TorrentConfig.DEFAULT_TRACKER_LISTS),
      http,
    )
    try {
      assertEquals(TorrentConfig.BEST_TRACKERS, source.extraTrackers())
    } finally {
      source.close()
    }
  }

  @Test
  fun trackerListMirror_mapsRawGitHubToJsDelivr() {
    assertEquals(
      "https://cdn.jsdelivr.net/gh/ngosang/trackerslist@master/trackers_best.txt",
      trackerListMirror(TorrentConfig.BEST_TRACKERS_URL),
    )
    assertEquals(
      "https://cdn.jsdelivr.net/gh/XIU2/TrackersListCollection@master/best.txt",
      trackerListMirror(TorrentConfig.XIU2_BEST_TRACKERS_URL),
    )
    assertNull(trackerListMirror(LIST))
    assertNull(trackerListMirror("https://raw.githubusercontent.com/a/b/refs/heads/main/x.txt"))
    assertNull(trackerListMirror("https://raw.githubusercontent.com/a/b/main"))
  }

  @Test
  fun failedGitHubDownload_usesTheMirror() = realTime {
    val mirror = checkNotNull(trackerListMirror(RAW_LIST))
    bodies[RAW_LIST] = null
    bodies[mirror] = "$HTTP\n"
    val subscription = subscription()
    subscription.subscribe(RAW_LIST)
    val state = subscription.state.first { it.updatedAt != null }
    assertEquals(listOf(HTTP), state.trackers)
    assertEquals(listOf(RAW_LIST, mirror), requests)
  }

  @Test
  fun severalLists_combineInOrderWithoutRepeats() = realTime {
    bodies[LIST] = "$UDP\n$HTTP\n"
    bodies[OTHER_LIST] = "$HTTP\n$OTHER\n"
    val lists = lists()
    lists.subscribe(listOf(LIST, OTHER_LIST))
    lists.state.first { states -> states.size == 2 && states.all { it.updatedAt != null } }
    published.waitFor { it == listOf(UDP, HTTP, OTHER) }

    lists.subscribe(listOf(OTHER_LIST))
    assertEquals(listOf(HTTP, OTHER), published.last())
    assertEquals(listOf(OTHER_LIST), lists.state.first { it.size == 1 }.map { it.url })
  }

  @Test
  fun lists_ignoreAddressesThatAreNotHttp() = realTime {
    val lists = lists()
    lists.subscribe(listOf("ftp://lists.example/x.txt", " ", LIST))
    assertEquals(listOf(LIST), lists.state.first { it.isNotEmpty() }.map { it.url })
  }

  @Test
  fun bestTrackers_areAllUsable() {
    val text = TorrentConfig.BEST_TRACKERS.joinToString("\n")
    assertEquals(TorrentConfig.BEST_TRACKERS, parseTrackerList(text))
  }

  @Test
  fun unsubscribe_clearsTheTrackers() = realTime {
    val subscription = subscription()
    subscription.subscribe(LIST)
    subscription.state.first { it.updatedAt != null }
    subscription.subscribe(null)
    assertEquals(TrackerListState(), subscription.state.value)
    assertEquals(emptyList(), published.last())
  }

  private fun realTime(block: suspend () -> Unit) = runTest {
    withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }
  }

  private fun lists() = TrackerLists(
    http = { TorrentHttp(http) },
    stateDirectory = directory,
    scope = scope,
    onTrackers = { synchronizedAdd(published, it) },
    bundled = { emptyList() },
    clock = clock,
  )

  // Waits until a published list matches [predicate].
  private suspend fun List<List<String>>.waitFor(predicate: (List<String>) -> Boolean) {
    while (lock.withLock { none(predicate) }) delay(10)
  }

  private fun subscription() = TrackerListSubscription(
    http = { TorrentHttp(http) },
    cacheFile = cacheFile,
    scope = scope,
    onTrackers = { synchronizedAdd(published, it) },
    bundled = { if (it == BUNDLED_LIST) listOf(BUNDLED) else emptyList() },
    clock = clock,
  )

  private val http = object : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("unused")

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      synchronizedAdd(requests, url)
      val text = if (url in bodies) bodies[url] else body
      onData((text ?: throw IOException("List unavailable")).encodeToByteArray())
    }

    override fun close() {}
  }

  private suspend fun <T> synchronizedAdd(list: MutableList<T>, value: T) {
    lock.withLock { list.add(value) }
  }

  private val lock = Mutex()

  private companion object {
    const val LIST = "https://lists.example/trackers_best.txt"
    const val BUNDLED_LIST = "https://lists.example/bundled.txt"
    const val BUNDLED = "udp://bundled.example:80/announce"
    const val OTHER_LIST = "https://lists.example/trackers_all.txt"
    const val RAW_LIST = "https://raw.githubusercontent.com/owner/lists/master/best.txt"
    const val OTHER = "udp://other.example:6969/announce"
    const val UDP = "udp://tracker.example:1337/announce"
    const val HTTP = "http://tracker.example:6969/announce"
  }
}
