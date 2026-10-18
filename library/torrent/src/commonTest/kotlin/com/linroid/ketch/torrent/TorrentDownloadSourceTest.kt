package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadConfig
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.api.ResolveInput
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.withoutBulkMetadata
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.DownloadContext
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.SelectionRequest
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.engine.canHandle
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.core.file.FileAccessor
import com.linroid.ketch.torrent.api.TorrentController
import com.linroid.ketch.torrent.api.TorrentFeatures
import com.linroid.ketch.torrent.crypto.sha1Digest
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.engine.TorrentBufferBudget
import com.linroid.ketch.torrent.metainfo.Bencode
import com.linroid.ketch.torrent.metainfo.InfoHash
import com.linroid.ketch.torrent.storage.TorrentCheckpoint
import com.linroid.ketch.torrent.storage.TorrentStorageException
import com.linroid.ketch.torrent.storage.TorrentV2PieceStore
import com.linroid.ketch.torrent.storage.torrentFileSystem
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
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
    val content = ResolveInput.Content("anything".encodeToByteArray(), "Ubuntu.TORRENT")
    assertTrue(source.canHandle(content))
  }

  @Test
  fun canHandleContent_bencodedDictionaryWithoutTorrentName() {
    val content = "d8:announce3:urle".encodeToByteArray()
    assertTrue(source.canHandle(ResolveInput.Content(content, null)))
    assertTrue(source.canHandle(ResolveInput.Content(content, "download")))
  }

  @Test
  fun canHandleContent_otherContent_returnsFalse() {
    assertFalse(source.canHandle(ResolveInput.Content("hello".encodeToByteArray(), "notes.txt")))
    assertFalse(source.canHandle(ResolveInput.Content("data".encodeToByteArray(), null)))
    assertFalse(source.canHandle(ResolveInput.Content("d".encodeToByteArray(), null)))
    assertFalse(source.canHandle(ResolveInput.Content(ByteArray(0), null)))
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
    // The reason travels with the error, URLs reduced, as remote clients see no cause.
    assertEquals("Piece hash mismatch", (corrupt as KetchError.SourceError).detail)
    val quoted = torrentFailure(IllegalStateException(
      "Rejected by https://tracker.test/announce?passkey=secret"))
    assertEquals("Rejected by https://tracker.test:443",
      assertIs<KetchError.SourceError>(quoted).detail)
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
      assertIs<KetchError.Disk>(assertFailsWith<KetchError> { single.download(context(), null) })
      engine.addTorrentError = timeout
      assertIs<KetchError.Network>(assertFailsWith<KetchError> { single.download(context(), null) })
      engine.addTorrentError = IllegalStateException("Too many open files")
      assertIs<KetchError.SourceError>(
        assertFailsWith<KetchError> { single.download(context(), null) })
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
    sourceState: TorrentSourceState? = null,
    outputPath: String? = null,
    segments: List<Segment>? = null,
    completed: Boolean = false,
  ) = SelectionRequest("task", fileIds, current, sourceState, resolved, segments, outputPath,
    completed)

  @Test
  fun features_listSelectionFeatures() {
    assertEquals(setOf(KetchFeatures.FILE_SELECTION,
      KetchFeatures.AWAIT_FILE_SELECTION, TorrentFeatures.CONTROL), source.features)
    assertEquals(setOf("file-selection", "v1", "v2", "hybrid", "utp", "mse-pe", "reachability"),
      source.torrentCapabilities)
    assertTrue(source.seedingTaskIds.value.isEmpty())
    assertFalse(source.restoresSeeding)
  }

  @Test
  fun torrentCapabilities_utpAndEncryptionOff_stillListsEngineProtocols() = runTest {
    val off = TorrentDownloadSource(
      TorrentConfig(utpEnabled = false, encryption = TorrentEncryption.DISABLED),
    )
    off.setUtpEnabled(false)
    off.setEncryption(TorrentEncryption.DISABLED)
    // Capabilities describe the backend; the settings only choose what it uses now.
    assertTrue("utp" in off.torrentCapabilities)
    assertTrue("mse-pe" in off.torrentCapabilities)
  }

  @Test
  fun ketchStatus_withTorrentSource_listsTorrentFeatures() = runTest {
    // A default folder of its own: the platform's needs an Android context.
    val ketch = Ketch(UnusedHttp, config = DownloadConfig(
      defaultDirectory = FileSystem.SYSTEM_TEMPORARY_DIRECTORY.toString()),
      additionalSources = listOf(TorrentDownloadSource()))
    try {
      val features = ketch.status().features
      assertTrue(KetchFeatures.FILE_SELECTION in features)
      assertTrue(KetchFeatures.AWAIT_FILE_SELECTION in features)
      assertTrue(TorrentFeatures.CONTROL in features)
      assertNotNull(ketch.controller(TorrentController))
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
      val state = TorrentSourceState(fixture.document.info.hash.hex, 40_000,
        encodeBase64(checkpoint.encode()), setOf("0"), output.toString(),
        encodeBase64(fixture.metainfo), version = 3, privacy = TorrentDiscoveryPrivacy.PUBLIC)
      // Something the task never created is where b would be saved.
      torrentFileSystem.write(output / "b") { writeUtf8("foreign") }
      val refused = assertFailsWith<IllegalStateException> {
        source.planSelection(selection(setOf("0", "1"), current = setOf("0"), sourceState = state,
          outputPath = output.toString()))
      }
      assertFalse(refused.message.orEmpty().contains(root.toString()))
      // Files the task already has are not in the way.
      assertFalse(source.planSelection(selection(setOf("0"), current = setOf("0"),
        sourceState = state, outputPath = output.toString())).changed)
      torrentFileSystem.delete(output / "b")
      val plan = source.planSelection(selection(setOf("0", "1"), current = setOf("0"),
        sourceState = state, outputPath = output.toString()))
      assertTrue(plan.expands)
      assertEquals(40_005L, plan.totalBytes)
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun planSelection_fileAddedBack_countsWhatItsCheckpointVerified() = runTest {
    // Pieces of 16 KiB: f1 spans pieces 6 to 10 and shares piece 10 with f2.
    val verified = BooleanArray(pack.pieceCount).also {
      it[7] = true
      it[10] = true
    }
    val checkpoint = TorrentCheckpoint("task", pack.metadata, "/ketch-plan/pack", setOf(0, 2),
      verified, emptyList(), emptyList())
    val state = TorrentSourceState(pack.metadata.infoHash.hex, pack.sizeOf(0, 2),
      encodeBase64(checkpoint.encode()), setOf("0", "2"), "/ketch-plan/pack",
      encodeBase64(pack.metainfo), version = 2, privacy = TorrentDiscoveryPrivacy.PUBLIC)
    val segments = listOf(Segment(0, 0, 99_999, 40_000), Segment(2, 100_000, 186_143))

    val plan = source.planSelection(selection(setOf("0", "1", "2"), current = setOf("0", "2"),
      sourceState = state, segments = segments))

    // f1 counts piece 7 whole and the 6_160 bytes of piece 10 before f2 starts.
    assertEquals(listOf(40_000L, 22_544L, 0L), plan.segments?.map { it.downloadedBytes })
  }

  @Test
  fun planSelection_v2FileAddedBack_countsWhatItsCheckpointVerified() = runTest {
    // Pieces of 32 KiB: a is pieces 0 and 1, b is piece 2.
    val fixture = TorrentV2Fixture.build(listOf("a" to 40_000, "b" to 20_000))
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-plan-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    val output = root / "out"
    torrentFileSystem.createDirectories(root)
    try {
      val store = TorrentV2PieceStore(fixture.document, output, setOf("0", "1"), "task",
        TorrentBufferBudget(4 * 1024 * 1024), Semaphore(1))
      val checkpoint = try {
        store.initialize()
        fixture.seed(store, 1..2)
        store.checkpoint()
      } finally {
        store.close()
      }
      // a was deselected since.
      val state = TorrentSourceState(fixture.document.info.hash.hex, 20_000,
        encodeBase64(checkpoint.encode()), setOf("1"), output.toString(),
        encodeBase64(fixture.metainfo), version = 3, privacy = TorrentDiscoveryPrivacy.PUBLIC)

      val plan = source.planSelection(selection(setOf("0", "1"), current = setOf("1"),
        sourceState = state, outputPath = output.toString(),
        segments = listOf(Segment(1, 0, 19_999, 20_000))))

      // The 7_232 bytes of a that piece 1 holds.
      assertEquals(listOf(7_232L, 20_000L), plan.segments?.map { it.downloadedBytes })
    } finally {
      torrentFileSystem.deleteRecursively(root, mustExist = false)
    }
  }

  @Test
  fun torrentFiles_listsEveryFileInOrder() = runTest {
    val resolved = source.resolveMetainfo(pack.metainfo)
    val files = assertNotNull(source.torrentFiles(null, resolved))
    assertEquals(listOf("0", "1", "2"), files.map { it.id })
    assertEquals(listOf("pack/f0", "pack/f1", "pack/f2"), files.map { it.name })
    assertEquals(pack.sizes.map { it.toLong() }, files.map { it.size })
    // A state older builds saved holds the metainfo the request lost.
    val legacy = TorrentSourceState(pack.metadata.infoHash.hex, resolved.totalBytes, "",
      setOf("0", "1", "2"), "", resolved.metadata.getValue(ResolvedSource.METAINFO_KEY),
      version = 2, privacy = TorrentDiscoveryPrivacy.PUBLIC)
    assertEquals(files, source.torrentFiles(legacy.saved(), resolved.withoutBulkMetadata()))
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
