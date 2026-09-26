package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.file.FileAccessor
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue

class TorrentDownloadSourceTest {

  private val source = TorrentDownloadSource()

  @Test
  fun canHandle_magnetUri() {
    assertTrue(source.canHandle("magnet:?xt=urn:btih:abc123"))
  }

  @Test
  fun canHandle_magnetUri_caseInsensitive() {
    assertTrue(source.canHandle("MAGNET:?xt=urn:btih:abc123"))
  }

  @Test
  fun canHandle_torrentUrl() {
    assertTrue(
      source.canHandle("https://example.com/file.torrent"),
    )
  }

  @Test
  fun canHandle_torrentUrlWithQueryParams() {
    assertTrue(
      source.canHandle("https://example.com/file.torrent?key=val"),
    )
  }

  @Test
  fun canHandle_httpUrl_returnsFalse() {
    assertFalse(source.canHandle("https://example.com/file.zip"))
  }

  @Test
  fun canHandle_ftpUrl_returnsFalse() {
    assertFalse(source.canHandle("ftp://example.com/file.zip"))
  }

  @Test
  fun canHandle_emptyString_returnsFalse() {
    assertFalse(source.canHandle(""))
  }

  @Test
  fun type_isTorrent() {
    kotlin.test.assertEquals("torrent", source.type)
  }

  @Test
  fun managesOwnFileIo_isTrue() {
    assertTrue(source.managesOwnFileIo)
  }

  @Test
  fun torrentFailure_onlyTimeoutsAreRetryable() = runTest {
    val disk = KetchError.Disk(IOException("full"))
    assertSame(disk, torrentFailure(disk))
    val canceled = KetchError.Canceled()
    assertSame(canceled, torrentFailure(canceled))

    val timeout = assertFailsWith<TimeoutCancellationException> {
      withTimeout(1) { awaitCancellation() }
    }
    assertTrue(assertIs<KetchError.Network>(torrentFailure(timeout)).isRetryable)

    val storage = IOException("No space left on device")
    val stored = assertIs<KetchError.Disk>(torrentFailure(TorrentStorageException(storage)))
    assertSame(storage, stored.cause)
    assertFalse(stored.isRetryable)
    assertIs<KetchError.Disk>(torrentFailure(IOException("read failed")))

    val corrupt = torrentFailure(IllegalStateException("Piece hash mismatch"))
    assertEquals("torrent", assertIs<KetchError.SourceError>(corrupt).sourceType)
    assertFalse(corrupt.isRetryable)
  }

  @Test
  fun download_failuresKeepTheirKetchTypeAndFreeTheActiveSlot() = runTest {
    val engine = FakeTorrentEngine()
    val single = TorrentDownloadSource(TorrentConfig(dhtEnabled = false, maxActiveTorrents = 1))
      .also { it.engineFactory = { engine } }
    val resolved = single.resolveMetainfo(Bencode.encode(mapOf("info" to mapOf(
      "name" to "payload", "length" to 4L, "piece length" to 4L,
      "pieces" to sha1Digest(byteArrayOf(1, 2, 3, 4))))))
    fun context() = DownloadContext(
      taskId = "task", url = resolved.url, request = DownloadRequest(resolved.url),
      fileAccessor = UnusedFileAccessor, segments = MutableStateFlow(emptyList()),
      onProgress = { _, _ -> }, throttle = {}, headers = emptyMap(), preResolved = resolved,
      outputPath = "unused-output",
    )
    val timeout = assertFailsWith<TimeoutCancellationException> {
      withTimeout(1) { awaitCancellation() }
    }
    try {
      // With one slot, each attempt only reaches the engine if the failed one released it.
      engine.addTorrentError = KetchError.Disk(IOException("full"))
      assertIs<KetchError.Disk>(assertFailsWith<KetchError> { single.download(context()) })
      engine.addTorrentError = timeout
      assertIs<KetchError.Network>(assertFailsWith<KetchError> { single.download(context()) })
      engine.addTorrentError = IllegalStateException("Too many open files")
      assertIs<KetchError.SourceError>(
        assertFailsWith<KetchError> { single.download(context()) })
      assertEquals(3, engine.removedTorrents.size)
    } finally { single.close() }
  }

  private object UnusedFileAccessor : FileAccessor {
    override suspend fun writeAt(offset: Long, data: ByteArray): Unit = error("Source owns I/O")
    override suspend fun flush(): Unit = error("Source owns I/O")
    override fun close() = Unit
    override suspend fun delete(): Unit = error("Source owns I/O")
    override suspend fun size(): Long = error("Source owns I/O")
    override suspend fun preallocate(size: Long): Unit = error("Source owns I/O")
  }
}
