package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.withoutBulkMetadata
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SelectionRequest
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.SourceResumeState
import com.linroid.ketch.core.file.FileAccessor
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okio.FileSystem
import okio.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
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
  fun canHandleContent_torrentFileName_caseInsensitive() {
    assertTrue(source.canHandleContent("anything".encodeToByteArray(), "Ubuntu.TORRENT"))
  }

  @Test
  fun canHandleContent_bencodedDictionaryWithoutTorrentName() {
    val content = "d8:announce3:urle".encodeToByteArray()
    assertTrue(source.canHandleContent(content, null))
    assertTrue(source.canHandleContent(content, "download"))
  }

  @Test
  fun canHandleContent_otherContent_returnsFalse() {
    assertFalse(source.canHandleContent("hello".encodeToByteArray(), "notes.txt"))
    assertFalse(source.canHandleContent("data".encodeToByteArray(), null))
    assertFalse(source.canHandleContent("d".encodeToByteArray(), null))
    assertFalse(source.canHandleContent(ByteArray(0), null))
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
  fun torrentPeerLimit_unsetUsesTorrentDefault_explicitValuesAreClamped() {
    // Unset (0) never falls back to Ketch's small HTTP segment count.
    assertEquals(100, torrentPeerLimit(0, default = 100, max = MAX_V1_PEERS))
    assertEquals(4, torrentPeerLimit(4, default = 100, max = MAX_V1_PEERS))
    assertEquals(512, torrentPeerLimit(100_000, default = 100, max = MAX_V1_PEERS))
    assertEquals(500, torrentPeerLimit(100_000, default = 100, max = MAX_V2_PEERS))
    assertEquals(500, torrentPeerLimit(0, default = 512, max = MAX_V2_PEERS))
    assertEquals(1, torrentPeerLimit(-3, default = 0, max = MAX_V1_PEERS))
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

  private val pack = MultiFileTorrent()

  private fun selection(
    fileIds: Set<String>?,
    resolved: com.linroid.ketch.api.ResolvedSource? = null,
    current: Set<String> = emptySet(),
    resumeState: SourceResumeState? = null,
    outputPath: String? = null,
    segments: List<Segment>? = null,
    completed: Boolean = false,
  ) = SelectionRequest("task", fileIds, current, resumeState, resolved, segments, outputPath,
    completed)

  @Test
  fun features_listSelectionFeatures() {
    assertEquals(setOf(KetchFeatures.TORRENT_FILE_SELECTION,
      KetchFeatures.TORRENT_AWAIT_FILE_SELECTION), source.features)
    assertEquals(setOf("file-selection", "v1", "v2", "hybrid"), source.torrentCapabilities)
    assertTrue(source.seedingTaskIds.value.isEmpty())
    assertFalse(source.restoresSeeding)
  }

  @Test
  fun ketchStatus_withTorrentSource_listsSelectionFeatures() = runTest {
    // A default folder of its own: the platform's needs an Android context.
    val ketch = Ketch(UnusedHttp, config = DownloadConfig(
      defaultDirectory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString()),
      additionalSources = listOf(TorrentDownloadSource()))
    try {
      val features = ketch.status().features
      assertTrue(KetchFeatures.TORRENT_FILE_SELECTION in features)
      assertTrue(KetchFeatures.TORRENT_AWAIT_FILE_SELECTION in features)
      assertFalse(KetchFeatures.TORRENT_CONTROL in features)
    } finally {
      ketch.close()
    }
  }

  @Test
  fun planSelection_clientSizesIgnored_totalFromMetainfo() = runTest {
    val resolved = source.resolveMetainfo(pack.metainfo)
    val forged = resolved.copy(files = resolved.files.map { it.copy(size = 1) })
    val plan = source.planSelection(selection(setOf("2", "0"), forged))
    assertEquals(pack.sizeOf(0, 2), plan.totalBytes)
    assertEquals(listOf("0", "2"), plan.fileIds.toList())
    assertTrue(plan.changed)
    assertFalse(plan.expands)
    assertEquals(listOf(Segment(0, 0, 99_999), Segment(2, 100_000, 186_143)), plan.segments)
    // A completed task keeps its files whole and gains the new one empty.
    val reopened = source.planSelection(selection(setOf("0", "1"), forged, current = setOf("0"),
      completed = true))
    assertTrue(reopened.expands)
    assertEquals(listOf(Segment(0, 0, 99_999, 100_000), Segment(1, 100_000, 169_999)),
      reopened.segments)
    // A paused task carries the progress of the files it keeps.
    val paused = source.planSelection(selection(setOf("0", "2"), forged, current = setOf("0"),
      segments = listOf(Segment(0, 0, 99_999, 40_000))))
    assertEquals(listOf(Segment(0, 0, 99_999, 40_000), Segment(2, 100_000, 186_143)),
      paused.segments)
    assertFalse(source.planSelection(selection(null, forged)).changed)
    for (invalid in listOf(setOf("3"), emptySet())) {
      assertFailsWith<IllegalArgumentException> {
        source.planSelection(selection(invalid, forged))
      }
    }
  }

  @Test
  fun planSelection_noMetadata_isIllegalState() = runTest {
    val resolved = source.resolveMetainfo(pack.metainfo)
    assertFailsWith<IllegalStateException> {
      source.planSelection(selection(setOf("0"), resolved.withoutBulkMetadata()))
    }
    assertFailsWith<IllegalStateException> { source.planSelection(selection(setOf("0"))) }
  }

  @Test
  fun planSelection_v2UnownedPathInTheWay_isRejected() = runTest {
    // Pieces of 32 KiB: a is pieces 0 and 1, b is piece 2.
    val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5))
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-plan-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val output = root / "out"
    torrentFileSystem.createDirectories(root)
    try {
      val store = TorrentV2PieceStore(fixture.document, output, setOf("0"), "task",
        TorrentBufferBudget(4 * 1024 * 1024), Semaphore(1))
      val checkpoint = try {
        store.initialize()
        fixture.seed(store, 0..1)
        store.checkpoint()
      } finally {
        store.close()
      }
      val state = SourceResumeState(TorrentDownloadSource.TYPE, Json.encodeToString(
        TorrentResumeState(fixture.document.info.hash.hex, 40_000,
          encodeBase64(checkpoint.encode()), setOf("0"), output.toString(),
          encodeBase64(fixture.metainfo), version = 3, privacy = TorrentDiscoveryPrivacy.PUBLIC)))
      // Something the task never created is where b would be saved.
      torrentFileSystem.write(output / "b") { writeUtf8("foreign") }
      val refused = assertFailsWith<IllegalStateException> {
        source.planSelection(selection(setOf("0", "1"), current = setOf("0"), resumeState = state,
          outputPath = output.toString()))
      }
      assertFalse(refused.message.orEmpty().contains(root.toString()))
      // Files the task already has are not in the way.
      assertFalse(source.planSelection(selection(setOf("0"), current = setOf("0"),
        resumeState = state, outputPath = output.toString())).changed)
      torrentFileSystem.delete(output / "b")
      val plan = source.planSelection(selection(setOf("0", "1"), current = setOf("0"),
        resumeState = state, outputPath = output.toString()))
      assertTrue(plan.expands)
      assertEquals(40_005L, plan.totalBytes)
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun resolveStored_returnsTheStoredMetainfo() = runTest {
    val resolved = source.resolveMetainfo(pack.metainfo)
    val stored = assertNotNull(source.resolveStored(
      source.buildResumeState(resolved, resolved.totalBytes)))
    assertEquals(resolved.metadata["infoHash"], stored.metadata["infoHash"])
    assertEquals(resolved.files, stored.files)
    // A state saved without metainfo rebuilds nothing.
    assertNull(source.resolveStored(TorrentDownloadSource.buildResumeState(
      pack.metadata.infoHash.hex, 1, ByteArray(0), setOf("0"), "/tmp/torrent")))
  }

  @Test
  fun torrentFiles_listsEveryFileInOrder() = runTest {
    val resolved = source.resolveMetainfo(pack.metainfo)
    val files = assertNotNull(source.torrentFiles(null, resolved))
    assertEquals(listOf("0", "1", "2"), files.map { it.id })
    assertEquals(listOf("pack/f0", "pack/f1", "pack/f2"), files.map { it.name })
    assertEquals(pack.sizes.map { it.toLong() }, files.map { it.size })
    // The saved state is enough once the request lost its metainfo.
    assertEquals(files, source.torrentFiles(source.buildResumeState(resolved,
      resolved.totalBytes), resolved.withoutBulkMetadata()))
    assertNull(source.torrentFiles(null, resolved.withoutBulkMetadata()))
    // Hybrid IDs are the v1 indices, so the padding file between a and b leaves a gap.
    val hybrid = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 5), hybrid = true)
    assertEquals(listOf("0", "2"), assertNotNull(source.torrentFiles(null,
      source.resolveMetainfo(hybrid.metainfo))).map { it.id })
    assertNull(source.liveTorrent("task"))
  }

  private object UnusedHttp : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("unused")
    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ): Unit = error("unused")
    override fun close() = Unit
  }
}
