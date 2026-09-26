package com.linroid.ketch.torrent

import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.KetchError
import kotlinx.coroutines.test.runTest
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
  fun resolve_magnetUri_timeoutReturnsNull_throwsNetworkError() =
    runTest {
      fakeEngine.fetchMetadataResult = null

      val error = assertFailsWith<KetchError.Network> {
        source.resolve(
          "magnet:?xt=urn:btih:$sampleHash",
          emptyMap(),
        )
      }
      assertNotNull(error.cause)
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

    val resolved = source.resolveContent(content, "pack.torrent")

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
  fun resolveContent_malformedBytes_throwsSourceError() = runTest {
    val error = assertFailsWith<KetchError.SourceError> {
      source.resolveContent("d8:announce".encodeToByteArray(), "broken.torrent")
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
