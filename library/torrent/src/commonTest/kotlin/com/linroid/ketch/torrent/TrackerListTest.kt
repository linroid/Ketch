package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.job
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import okio.FileSystem
import okio.IOException
import okio.Path
import kotlin.concurrent.Volatile
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
    // Moved by the tests while the lists' coroutines read it.
    @Volatile var now = Instant.fromEpochMilliseconds(1_790_000_000_000)
    override fun now(): Instant = now
  }
  private var body: String? = "$UDP\n$HTTP\n"
  // Bodies by URL, for lists that differ; others get [body].
  private val bodies = mutableMapOf<String, String?>()
  // Added to by the lists' coroutines while the tests read them.
  private val requests = MutableStateFlow(emptyList<String>())
  private val published = MutableStateFlow(emptyList<List<String>>())

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
  fun parseTrackerList_keepsAtMostTheExtraTrackerLimit() {
    val text = (1..200).joinToString("\n") { "udp://tracker$it.example:1337/announce" }
    val parsed = parseTrackerList(text)
    assertEquals(MAX_ADDITIONAL_TRACKERS, parsed.size)
    assertEquals("udp://tracker1.example:1337/announce", parsed.first())
  }

  @Test
  fun subscribe_ignoresNonHttpUrl() = runTest {
    val subscription = subscription()
    subscription.subscribe("ftp://example.org/trackers.txt")
    assertEquals(TrackerListState(), subscription.state.value)
    assertEquals(emptyList(), requests.value)
  }

  @Test
  fun subscribe_downloadsPublishesAndSavesTheList() = realTime {
    val subscription = subscription()
    subscription.subscribe(LIST)
    val state = subscription.state.first { it.updatedAt != null }
    assertEquals(listOf(UDP, HTTP), state.trackers)
    assertEquals(clock.now, state.updatedAt)
    assertEquals(listOf(LIST), requests.value)
    // onTrackers gets the download just after the state shows it.
    assertEquals(listOf(emptyList(), listOf(UDP, HTTP)), published.first { it.size >= 2 })
    assertTrue(FileSystem.SYSTEM.exists(cacheFile))
  }

  @Test
  fun subscribe_usesAFreshCopyWithoutDownloading() = realTime {
    saveCopy()
    val saved = clock.now
    clock.now = saved + 23.hours

    val restarted = subscription()
    restarted.subscribe(LIST)
    val state = restarted.state.first { it.trackers.isNotEmpty() }
    assertEquals(listOf(UDP, HTTP), state.trackers)
    assertEquals(saved, state.updatedAt)
    assertEquals(emptyList(), requests.value)
  }

  @Test
  fun subscribe_downloadsAgainWhenTheCopyIsADayOld() = realTime {
    saveCopy()
    clock.now += 25.hours
    body = "$HTTP\n"

    val restarted = subscription()
    restarted.subscribe(LIST)
    assertEquals(listOf(HTTP), restarted.state.first { it.updatedAt == clock.now }.trackers)
    assertEquals(listOf(LIST), requests.value)
  }

  @Test
  fun subscribe_ignoresTheCopyOfAnotherList() = realTime {
    saveCopy()

    val other = subscription()
    other.subscribe(OTHER_LIST)
    other.state.first { it.updatedAt != null }
    assertEquals(listOf(OTHER_LIST), requests.value)
  }

  @Test
  fun failedDownload_keepsTheOlderCopy() = realTime {
    saveCopy()
    val saved = clock.now
    clock.now += 25.hours
    body = null

    val restarted = subscription()
    restarted.subscribe(LIST)
    val state = restarted.state.first { it.failed }
    assertEquals(listOf(UDP, HTTP), state.trackers)
    assertEquals(saved, state.updatedAt)
    assertEquals(listOf(UDP, HTTP), published.value.last())
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
    assertEquals(listOf(LIST, LIST), requests.value)
  }

  @Test
  fun bundledCopy_isUsedUntilTheFirstDownloadSucceeds() = realTime {
    body = null
    val subscription = subscription()
    subscription.subscribe(BUNDLED_LIST)
    val state = subscription.state.first { it.failed }
    assertEquals(listOf(BUNDLED), state.trackers)
    assertNull(state.updatedAt)
    assertEquals(listOf(BUNDLED), published.value.last())

    body = "$UDP\n"
    subscription.refresh()
    assertEquals(listOf(UDP), subscription.state.first { it.updatedAt != null }.trackers)
  }

  @Test
  fun subscribe_returnsWithTheSavedCopyPublished() = realTime {
    saveCopy()
    body = null

    val restarted = subscription()
    restarted.subscribe(LIST)
    assertEquals(listOf(UDP, HTTP), restarted.state.value.trackers)
    assertEquals(listOf(UDP, HTTP), published.value.single())
  }

  @Test
  fun subscribe_returnsWithTheShippedCopyPublished() = realTime {
    body = null
    val subscription = subscription()
    subscription.subscribe(BUNDLED_LIST)
    assertEquals(listOf(BUNDLED), subscription.state.value.trackers)
    assertEquals(listOf(BUNDLED), published.value.single())
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
    assertEquals(listOf(RAW_LIST, mirror), requests.value)
  }

  @Test
  fun severalLists_combineInOrderWithoutRepeats() = realTime {
    bodies[LIST] = "$UDP\n$HTTP\n"
    bodies[OTHER_LIST] = "$HTTP\n$OTHER\n"
    val lists = lists()
    lists.subscribe(listOf(LIST, OTHER_LIST))
    lists.state.first { states -> states.size == 2 && states.all { it.updatedAt != null } }
    published.first { listOf(UDP, HTTP, OTHER) in it }

    lists.subscribe(listOf(OTHER_LIST))
    assertEquals(listOf(HTTP, OTHER), published.value.last())
    assertEquals(listOf(OTHER_LIST), lists.state.first { it.size == 1 }.map { it.url })
  }

  @Test
  fun lists_ignoreAddressesThatAreNotHttp() = realTime {
    val lists = lists()
    lists.subscribe(listOf("ftp://lists.example/x.txt", " ", LIST))
    assertEquals(listOf(LIST), lists.state.first { it.isNotEmpty() }.map { it.url })
  }

  @Test
  fun lists_keepTheShippedTrackersWhileSubscribing() = realTime {
    body = null
    val lists = lists()
    lists.subscribe(listOf(BUNDLED_LIST, LIST))
    assertEquals(listOf(listOf(BUNDLED)), published.value.distinct())
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
    assertEquals(emptyList(), published.value.last())
  }

  // Stops the lists before the test ends, so cleanUp never deletes the directory while a
  // download is still saving its copy there.
  private fun realTime(block: suspend () -> Unit) = runTest {
    try {
      withContext(Dispatchers.Default) { withTimeout(10_000) { block() } }
    } finally {
      scope.coroutineContext.job.cancelAndJoin()
    }
  }

  private fun lists() = TrackerLists(
    http = { TorrentHttp(http) },
    stateDirectory = directory,
    scope = scope,
    onTrackers = { trackers -> published.update { it + listOf(trackers) } },
    bundled = ::bundled,
    clock = clock,
  )

  private fun subscription() = TrackerListSubscription(
    http = { TorrentHttp(http) },
    cacheFile = cacheFile,
    scope = scope,
    onTrackers = { trackers -> published.update { it + listOf(trackers) } },
    bundled = ::bundled,
    clock = clock,
  )

  private fun bundled(url: String) = if (url == BUNDLED_LIST) listOf(BUNDLED) else emptyList()

  // Saves a copy of the list as an earlier run would, then stops that run, so it neither
  // downloads again when the test moves the clock nor adds to what the test checks.
  private suspend fun saveCopy() {
    val earlier = subscription()
    earlier.subscribe(LIST)
    earlier.state.first { it.updatedAt != null }
    earlier.subscribe(null)
    requests.value = emptyList()
    published.value = emptyList()
  }

  private val http = object : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("unused")

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      requests.update { it + url }
      val text = if (url in bodies) bodies[url] else body
      onData((text ?: throw IOException("List unavailable")).encodeToByteArray())
    }

    override fun close() {}
  }

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
