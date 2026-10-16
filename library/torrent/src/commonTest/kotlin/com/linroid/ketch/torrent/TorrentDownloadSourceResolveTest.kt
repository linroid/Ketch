package com.linroid.ketch.torrent

import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.LogLevel
import com.linroid.ketch.api.log.Logger
import com.linroid.ketch.core.engine.resolve
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.metainfo.TorrentMetadata
import kotlin.test.assertNull
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Tests for [TorrentDownloadSource.resolve] using [FakeTorrentEngine].
 */
class TorrentDownloadSourceResolveTest {

  private val fakeEngine = FakeTorrentEngine()
  private val source = TorrentDownloadSource().also {
    it.engineFactory = { fakeEngine }
  }

  private val sampleHash =
    "aabbccddee11223344556677889900aabbccddee"

  private fun singleFileMetadata(
    name: String = "test.txt",
    size: Long = 1024L,
  ) = TorrentMetadata(
    infoHash = InfoHash.fromHex(sampleHash),
    name = name,
    pieceLength = 262144L,
    totalBytes = size,
    files = listOf(
      TorrentMetadata.TorrentFile(
        index = 0,
        path = name,
        size = size,
      ),
    ),
    trackers = listOf("http://tracker.example.com/announce"),
    comment = "Test torrent",
  )

  private fun multiFileMetadata() = TorrentMetadata(
    infoHash = InfoHash.fromHex(sampleHash),
    name = "my-pack",
    pieceLength = 65536L,
    totalBytes = 3000L,
    files = listOf(
      TorrentMetadata.TorrentFile(0, "my-pack/a.txt", 1000L),
      TorrentMetadata.TorrentFile(1, "my-pack/b.txt", 800L),
      TorrentMetadata.TorrentFile(2, "my-pack/c.txt", 1200L),
    ),
  )

  // -- resolve via magnet URI --

  @Test
  fun resolve_magnetUri_singleFile() = runTest {
    fakeEngine.fetchMetadataResult = singleFileMetadata()

    val resolved = source.resolve(
      "magnet:?xt=urn:btih:$sampleHash",
      emptyMap(),
    )

    assertTrue(fakeEngine.started)
    assertEquals("magnet:?xt=urn:btih:$sampleHash", resolved.url)
    assertEquals("torrent", resolved.sourceType)
    assertEquals(1024L, resolved.totalBytes)
    assertTrue(resolved.supportsResume)
    assertEquals("test.txt", resolved.suggestedFileName)
    assertEquals(1, resolved.maxSegments)
    assertEquals(1, resolved.files.size)
    assertEquals("0", resolved.files[0].id)
    assertEquals("test.txt", resolved.files[0].name)
    assertEquals(1024L, resolved.files[0].size)
    assertEquals(FileSelectionMode.MULTIPLE, resolved.selectionMode)
  }

  @Test
  fun resolve_magnetUri_multiFile() = runTest {
    fakeEngine.fetchMetadataResult = multiFileMetadata()

    val resolved = source.resolve(
      "magnet:?xt=urn:btih:$sampleHash",
      emptyMap(),
    )

    assertEquals(3000L, resolved.totalBytes)
    assertEquals("my-pack", resolved.suggestedFileName)
    assertEquals(3, resolved.maxSegments)
    assertEquals(3, resolved.files.size)
    assertEquals("0", resolved.files[0].id)
    assertEquals("my-pack/a.txt", resolved.files[0].name)
    assertEquals(1000L, resolved.files[0].size)
    assertEquals("2", resolved.files[2].id)
    assertEquals("my-pack/c.txt", resolved.files[2].name)
    assertEquals(1200L, resolved.files[2].size)
  }

  @Test
  fun resolve_magnetUri_metadataContainsInfoHash() = runTest {
    fakeEngine.fetchMetadataResult = singleFileMetadata()

    val resolved = source.resolve(
      "magnet:?xt=urn:btih:$sampleHash",
      emptyMap(),
    )

    assertEquals(sampleHash, resolved.metadata["infoHash"])
    assertEquals("test.txt", resolved.metadata["name"])
    assertEquals("262144", resolved.metadata["pieceLength"])
    assertEquals("Test torrent", resolved.metadata["comment"])
  }

  @Test
  fun resolve_magnetWithoutMetadata_looksAgainUntilFound() = runTest {
    fakeEngine.fetchMetadataResults.addAll(listOf(null, null))
    fakeEngine.fetchMetadataResult = singleFileMetadata()
    val resolved = source.resolve("magnet:?xt=urn:btih:$sampleHash")
    assertEquals("test.txt", resolved.suggestedFileName)
    assertEquals(3, fakeEngine.fetchMetadataCalls)
  }

  @Test
  fun resolve_v2MagnetNotFoundWhileInfoIsLogged_looksAgain() = runTest {
    withContext(Dispatchers.Default) {
      val lines = Channel<String>(Channel.UNLIMITED)
      KetchLogger.setLogger(object : Logger {
        override fun v(message: String) = Unit
        override fun d(message: String) = Unit
        override fun i(message: String) { lines.trySend(message) }
        override fun w(message: String, throwable: Throwable?) = Unit
        override fun e(message: String, throwable: Throwable?) = Unit
        override fun isLoggable(level: LogLevel): Boolean = level >= LogLevel.INFO
      })
      // No DHT, LAN discovery or trackers: each lookup finds nothing and times out.
      val real = TorrentDownloadSource(TorrentConfig(dhtEnabled = false, portMapping = false,
        localDiscovery = false)).apply { magnetLookupTime = 200.milliseconds }
      val resolving = async { real.resolve("magnet:?xt=urn:btmh:1220${"ab".repeat(32)}") }
      try {
        withTimeout(20_000) {
          var lookups = 0
          while (lookups < 2) {
            if ("No metadata for magnet ${"ab".repeat(6)}" in lines.receive()) lookups++
          }
        }
        assertTrue(resolving.isActive)
      } finally {
        KetchLogger.setLogger(Logger.None)
        resolving.cancelAndJoin()
        real.close()
      }
    }
  }

  @Test
  fun resolve_magnetWithOtherNetworkError_fails() = runTest {
    fakeEngine.fetchMetadataError = KetchError.Network(IOException("port in use"))
    assertFailsWith<KetchError.Network> {
      source.resolve("magnet:?xt=urn:btih:$sampleHash")
    }
    assertEquals(1, fakeEngine.fetchMetadataCalls)
  }

  @Test
  fun resolve_magnetWithoutMetadata_looksUntilTheCallerStops() = runTest {
    fakeEngine.fetchMetadataResult = null

    val resolved = withTimeoutOrNull(10.minutes) {
      source.resolve("magnet:?xt=urn:btih:$sampleHash")
    }

    assertNull(resolved)
    assertTrue(fakeEngine.fetchMetadataCalls > 1)
  }

  @Test
  fun resolve_magnetUri_engineError_wrapsAsSourceError() = runTest {
    fakeEngine.fetchMetadataError =
      RuntimeException("Engine failed")

    val error = assertFailsWith<KetchError.SourceError> {
      source.resolve(
        "magnet:?xt=urn:btih:$sampleHash",
        emptyMap(),
      )
    }
    assertEquals("torrent", error.sourceType)
    assertIs<RuntimeException>(error.cause)
  }

  // -- resolve via .torrent content --

  @Test
  fun resolveContent_metainfoBytes_resolvesWithoutEngine() = runTest {
    val content = Bencode.encode(mapOf(
      "announce" to "http://tracker.example.com/announce",
      "info" to mapOf(
        "name" to "pack", "piece length" to 16384L, "pieces" to ByteArray(20),
        "files" to listOf(
          mapOf("length" to 1L, "path" to listOf("a.txt")),
          mapOf("length" to 2L, "path" to listOf("b.txt")),
        ),
      ),
    ))

    val resolved = source.resolve(ResolveInput.Content(content, "pack.torrent"))

    val hash = TorrentMetadata.fromBencode(content).infoHash.hex
    assertEquals("torrent:$hash", resolved.url)
    assertEquals("torrent", resolved.sourceType)
    assertEquals(3L, resolved.totalBytes)
    assertEquals("pack", resolved.suggestedFileName)
    assertEquals(listOf("0", "1"), resolved.files.map { it.id })
    assertEquals(hash, resolved.metadata["infoHash"])
    assertNotNull(resolved.metadata["metainfo"])
    assertFalse(fakeEngine.started)
  }

  @Test
  fun resolveContent_v2MetainfoWithAWebSocketTracker_resolves() = runTest {
    val fixture = TorrentV2Fixture.build(listOf("a.bin" to 40_000), hybrid = true)
    val tiers = listOf(listOf("wss://tracker.test/announce"), listOf("udp://t.test:1"))
    val content = withTrackers(fixture.metainfo, tiers)

    val resolved = source.resolve(ResolveInput.Content(content, "pack.torrent"))

    assertEquals(fixture.document.info.hash.hex, resolved.metadata["infoHash"])
    assertEquals(listOf(listOf("udp://t.test:1")), sourceTrackerTiers(content, 4 * 1024 * 1024))
  }

  /** [metainfo] with an `announce-list` of [tiers] first, which sorts before `info`. */
  private fun withTrackers(metainfo: ByteArray, tiers: List<List<String>>): ByteArray =
    "d13:announce-list".encodeToByteArray() + Bencode.encode(tiers) +
      metainfo.copyOfRange(1, metainfo.size)

  @Test
  fun resolveContent_privateTorrentWithoutAUsableTracker_refused() = runTest {
    val v2 = TorrentV2Fixture.build(listOf("a.bin" to 40_000), privateTorrent = true).metainfo
    val v1 = Bencode.encode(mapOf("info" to mapOf("name" to "a.bin", "length" to 1L,
      "piece length" to 16_384L, "pieces" to ByteArray(20), "private" to 1L)))
    val webSocket = listOf(listOf("wss://tracker.test/announce"))
    for (metainfo in listOf(v2, v1)) {
      // Its peers come only from trackers, and Ketch cannot announce to a WebSocket one.
      val content = withTrackers(metainfo, webSocket)
      val error = assertFailsWith<KetchError.SourceError> {
        source.resolve(ResolveInput.Content(content, "private.torrent"))
      }
      assertTrue("no http, https or udp tracker" in error.cause?.message.orEmpty())
      // A saved task's metainfo still reads.
      TorrentFileTable.parse(content, TorrentConfig())
      source.resolve(ResolveInput.Content(
        withTrackers(metainfo, webSocket + listOf(listOf("udp://t.test:1"))), "private.torrent"))
    }
  }

  @Test
  fun resolveMetainfo_trackerOnlyWithoutAUsableTracker_refused() {
    val metainfo = withTrackers(TorrentV2Fixture.build(listOf("a.bin" to 40_000)).metainfo,
      listOf(listOf("wss://tracker.test/announce")))
    assertFailsWith<IllegalArgumentException> {
      source.resolveMetainfo(metainfo, TorrentDiscoveryPrivacy.TRACKER_ONLY)
    }
    source.resolveMetainfo(metainfo, TorrentDiscoveryPrivacy.PUBLIC)
    // A web seed finds the content without peers.
    val seeded = TorrentV2Fixture.build(listOf("a.bin" to 40_000),
      webSeeds = listOf("https://mirror.test/")).metainfo
    source.resolveMetainfo(seeded, TorrentDiscoveryPrivacy.TRACKER_ONLY)
  }

  @Test
  fun resolveContent_unsafeNamesAndPadding_listsTheStoredFiles() = runTest {
    val content = Bencode.encode(mapOf("info" to mapOf(
      "name" to "Show: Part 2", "piece length" to 4L, "pieces" to ByteArray(40),
      "files" to listOf(
        mapOf("length" to 3L, "path" to listOf("Who?.flac")),
        mapOf("attr" to "p", "length" to 1L, "path" to listOf(".pad", "1")),
        mapOf("length" to 4L, "path" to listOf("b"))
      )
    )))

    val resolved = source.resolve(ResolveInput.Content(content, "show.torrent"))

    assertEquals(listOf("0", "2"), resolved.files.map { it.id })
    assertEquals(listOf("Show_ Part 2/Who_.flac", "Show_ Part 2/b"),
      resolved.files.map { it.name })
    // The padding is never downloaded.
    assertEquals(7L, resolved.totalBytes)
  }

  @Test
  fun resolveContent_malformedBytes_throwsSourceError() = runTest {
    val error = assertFailsWith<KetchError.SourceError> {
      source.resolve(ResolveInput.Content("d8:announce".encodeToByteArray(), "broken.torrent"))
    }
    assertEquals("torrent", error.sourceType)
  }

  // -- resolve via .torrent URL --

  // -- Engine lifecycle --

  @Test
  fun resolve_startsEngineIfNotRunning() = runTest {
    fakeEngine.fetchMetadataResult = singleFileMetadata()

    source.resolve(
      "magnet:?xt=urn:btih:$sampleHash",
      emptyMap(),
    )

    assertTrue(fakeEngine.started)
    assertTrue(fakeEngine.isRunning)
  }

  @Test
  fun resolve_reusesRunningEngine() = runTest {
    fakeEngine.fetchMetadataResult = singleFileMetadata()

    // First call starts engine
    source.resolve(
      "magnet:?xt=urn:btih:$sampleHash",
      emptyMap(),
    )
    assertTrue(fakeEngine.started)

    // Second call should reuse the same engine (no stop/restart)
    source.resolve(
      "magnet:?xt=urn:btih:$sampleHash",
      emptyMap(),
    )
    // Still the same engine instance
    assertTrue(fakeEngine.isRunning)
  }

  // -- KetchError passthrough --

  @Test
  fun resolve_ketchErrorPassedThrough_notWrapped() = runTest {
    val original = KetchError.AuthenticationFailed("FTP")
    fakeEngine.fetchMetadataError = original

    val error = assertFailsWith<KetchError.AuthenticationFailed> {
      source.resolve(
        "magnet:?xt=urn:btih:$sampleHash",
        emptyMap(),
      )
    }
    assertEquals(original, error)
  }
}
