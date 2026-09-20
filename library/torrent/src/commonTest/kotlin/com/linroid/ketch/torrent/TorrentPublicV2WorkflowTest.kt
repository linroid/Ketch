package com.linroid.ketch.torrent

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchError
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.core.Ketch
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskStore
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import okio.use

class TorrentPublicV2WorkflowTest {
  @Test fun pureV2SelectionSurvivesKetchRecreationAndRechecksFiles() = runTest {
    restart(hybrid = false)
  }

  @Test fun hybridSelectionSurvivesKetchRecreationAndRechecksFiles() = runTest {
    restart(hybrid = true)
  }

  @Test fun creationJournalRecoversBeforeFirstSourceCheckpoint() = runTest {
    restart(hybrid = false, staleCheckpoint = true)
  }

  @Test fun pureV2MagnetAuthenticatesMetadataAndExternalPieceLayers() = runTest {
    magnet(hybrid = false)
  }

  @Test fun hybridSelectionUsesOriginalFileIdAfterPadding() = runTest {
    magnet(hybrid = true, selectLast = true)
  }

  @Test fun hybridMagnetRetainsBothExactTopics() = runTest {
    magnet(hybrid = true)
  }

  @Test fun taskRateLimitCanBeRemovedWhileV2WritesAreWaiting() = runTest {
    fixture(false, payloadLength = 131_075) { peer ->
      peer.remaining.complete(Unit)
      val root = temporaryDirectory()
      val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false), peer.http)
      val ketch = Ketch(peer.http, additionalSources = listOf(source))
      try {
        ketch.start()
        val task = ketch.download(DownloadRequest("http://fixture/file.torrent",
          destination = Destination((root / "out").toString()), selectedFileIds = setOf("0"),
          speedLimit = SpeedLimit.of(1)))
        delay(300)
        assertFalse(task.state.value is DownloadState.Completed)
        // The shared limiter allows its documented initial 64 KiB burst.
        assertTrue(task.segments.value.sumOf { it.downloadedBytes } <= 65_536)
        task.setSpeedLimit(SpeedLimit.Unlimited)
        task.await().getOrThrow()
      } finally { ketch.close(); torrentFileSystem.deleteRecursively(root) }
    }
  }

  @Test fun magnetDoesNotResolveWhenPieceLayerProofIsCorrupt() = runTest {
    fixture(false) { peer ->
      peer.corruptProof = true
      val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false,
        metadataTimeoutSeconds = 1), peer.http)
      try {
        val uri = MagnetUri(peer.document.identity,
          trackers = listOf("http://fixture/announce")).toUri()
        assertFailsWith<KetchError.Network> {
          source.resolve(uri, TorrentDiscoveryPrivacy.TRACKER_ONLY)
        }
        assertTrue(peer.hashRequests > 0)
      } finally { source.close() }
    }
  }

  @Test fun closingSourceJoinsOutstandingV2MetadataResolution() = runTest {
    fixture(false) { peer -> coroutineScope {
      peer.corruptProof = true
      val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false), peer.http)
      val uri = MagnetUri(peer.document.identity,
        trackers = listOf("http://fixture/announce")).toUri()
      val resolving = async { source.resolve(uri, TorrentDiscoveryPrivacy.TRACKER_ONLY) }
      try {
        peer.requestedHashes.await()
        source.close()
        assertFailsWith<CancellationException> { resolving.await() }
      } finally { source.close(); resolving.cancelAndJoin() }
    } }
  }

  private suspend fun magnet(
    hybrid: Boolean,
    selectLast: Boolean = false,
  ) = fixture(hybrid) { peer ->
    peer.remaining.complete(Unit)
    val source = TorrentDownloadSource(TorrentConfig(dhtEnabled = false), peer.http)
    val ketch = Ketch(peer.http, additionalSources = listOf(source))
    val root = temporaryDirectory()
    try {
      val uri = MagnetUri(peer.document.identity, trackers = listOf("http://fixture/announce"))
        .toUri()
      val resolved = source.resolve(uri, TorrentDiscoveryPrivacy.TRACKER_ONLY)
      assertEquals(peer.document.info.hash.hex, resolved.metadata["infoHash"])
      assertEquals(2, resolved.files.size)
      assertEquals(setOf("0", if (hybrid) "2" else "1"), resolved.files.map { it.id }.toSet())
      ketch.start()
      val selected = if (selectLast) resolved.files.last().id else resolved.files.first().id
      val task = ketch.download(DownloadRequest(uri, destination = Destination((root / "out")
        .toString()), resolvedSource = resolved, selectedFileIds = setOf(selected)))
      task.await().getOrThrow()
      val file = if (selectLast) "b" else "a"
      assertContentEquals(if (selectLast) byteArrayOf(7) else peer.payload,
        torrentFileSystem.read(root / "out" / file) { readByteArray() })
      assertFalse(torrentFileSystem.exists(root / "out" / if (selectLast) "a" else "b"))
      assertTrue(peer.hashRequests > 0)
    } finally { ketch.close(); torrentFileSystem.deleteRecursively(root) }
  }

  private suspend fun restart(
    hybrid: Boolean,
    staleCheckpoint: Boolean = false,
  ) = fixture(hybrid) { peer ->
    val root = temporaryDirectory()
    val recordPath = root / "tasks.json"
    val records = Records(recordPath)
    fun source() = TorrentDownloadSource(TorrentConfig(dhtEnabled = false), peer.http)
    val firstSource = source()
    val first = Ketch(peer.http, taskStore = records, additionalSources = listOf(firstSource))
    var second: Ketch? = null
    try {
      first.start()
      val task = first.download(DownloadRequest("http://fixture/file.torrent",
        destination = Destination((root / "out").toString()), selectedFileIds = setOf("0")))
      task.segments.first { values -> values.sumOf { it.downloadedBytes } >= 16_384 }
      task.setConnections(1)
      delay(100)
      task.pause()
      assertTrue(task.state.value is DownloadState.Paused)
      val stored = requireNotNull(records.load(task.taskId))
      assertTrue(stored.sourceResumeState?.data?.contains(peer.document.info.hash.hex) == true)
      first.close()
      if (staleCheckpoint) {
        records.save(stored.copy(sourceResumeState = firstSource.buildResumeState(
          source().resolveMetainfo(peer.metainfo), peer.payload.size.toLong())))
      }
      // Persisted verified bits cannot authorize payload modified while the process was away.
      torrentFileSystem.openReadWrite(root / "out" / "a").use {
        it.write(0, byteArrayOf(99), 0, 1)
        it.flush()
      }
      peer.remaining.complete(Unit)
      val replacement = Ketch(peer.http, taskStore = Records(recordPath),
        additionalSources = listOf(source()))
      second = replacement
      replacement.start()
      val restored = replacement.tasks.value.single()
      assertEquals(task.taskId, restored.taskId)
      restored.resume()
      restored.await().getOrThrow()
      assertContentEquals(peer.payload, torrentFileSystem.read(root / "out" / "a") {
        readByteArray()
      })
      assertFalse(torrentFileSystem.exists(root / "out" / "b"))
      assertEquals(peer.payload.size.toLong(), restored.segments.value.sumOf { it.downloadedBytes })
      torrentFileSystem.write(root / "out" / "foreign") { writeUtf8("keep") }
      restored.remove(deleteFiles = true)
      assertFalse(torrentFileSystem.exists(root / "out" / "a"))
      assertEquals("keep", torrentFileSystem.read(root / "out" / "foreign") { readUtf8() })
      assertFalse(torrentFileSystem.exists(v2CreationLog(root / "out", restored.taskId)))
    } finally {
      first.close()
      second?.close()
      torrentFileSystem.deleteRecursively(root)
    }
  }

  private fun temporaryDirectory() = (FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
    "ketch-public-v2-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}").also {
    torrentFileSystem.createDirectory(it)
  }

  private suspend fun fixture(
    hybrid: Boolean,
    payloadLength: Int = 32_771,
    body: suspend (Peer) -> Unit,
  ) =
    withContext(Dispatchers.Default) {
      withTimeout(20_000) {
        coroutineScope {
          val peer = Peer(hybrid, payloadLength)
          peer.start(this)
          try { body(peer) } finally { peer.close() }
        }
      }
    }

  private class Records(private val path: Path) : TaskStore {
    private val mutex = Mutex()
    private fun read(): List<TaskRecord> = if (torrentFileSystem.exists(path)) {
      Json.decodeFromString(torrentFileSystem.read(path) { readUtf8() })
    } else emptyList()
    override suspend fun save(record: TaskRecord): Unit = mutex.withLock {
      val records = read().filter { it.taskId != record.taskId } + record
      torrentFileSystem.write(path) { writeUtf8(Json.encodeToString(records)) }
    }
    override suspend fun load(taskId: String): TaskRecord? = mutex.withLock {
      read().firstOrNull { it.taskId == taskId }
    }
    override suspend fun loadAll(): List<TaskRecord> = mutex.withLock { read() }
    override suspend fun remove(taskId: String): Unit = mutex.withLock {
      val records = read().filter { it.taskId != taskId }
      torrentFileSystem.write(path) { writeUtf8(Json.encodeToString(records)) }
    }
  }

  private class Peer(hybrid: Boolean, payloadLength: Int) {
    val payload = ByteArray(payloadLength) { (it * 13).toByte() }
    private val blocks = payload.asList().chunked(16_384).map { it.toByteArray() }
    private val hashes = blocks.map(::sha256Digest)
    private val leaves = hashes.toMutableList().apply {
      while (size.countOneBits() != 1) add(ByteArray(32))
    }
    private val root = run {
      var level = leaves.toList()
      while (level.size > 1) level = level.chunked(2).map { sha256Digest(it[0] + it[1]) }
      level.single()
    }
    private val info = mutableMapOf<String, Any>("meta version" to 2L, "name" to "pack",
      "piece length" to 16_384L, "file tree" to mapOf(
        "a" to mapOf("" to mapOf("length" to payload.size.toLong(), "pieces root" to root)),
        "b" to mapOf("" to mapOf("length" to 1L, "pieces root" to sha256Digest(byteArrayOf(7))))
      )
    ).apply {
      if (hybrid) {
        put("files", listOf(mapOf("path" to listOf("a"), "length" to payload.size.toLong()),
          mapOf("attr" to "p", "length" to 16_381L),
          mapOf("path" to listOf("b"), "length" to 1L)))
        put("pieces", blocks.dropLast(1).fold(ByteArray(0)) { a, b -> a + sha1Digest(b) } +
          sha1Digest(blocks.last() + ByteArray(16_381)) + sha1Digest(byteArrayOf(7)))
      }
    }
    val metainfo = Bencode.encode(mapOf("info" to info,
      "announce" to "http://fixture/announce", "piece layers" to mapOf(root.toByteString() to
        hashes.fold(ByteArray(0)) { a, b -> a + b })))
    val document = TorrentV2Document.parse(metainfo)
    val remaining = CompletableDeferred<Unit>()
    val requestedHashes = CompletableDeferred<Unit>()
    var hashRequests = 0
    var corruptProof = false
    private val network = createTorrentNetwork()
    private lateinit var listener: TorrentListener
    private lateinit var job: kotlinx.coroutines.Job
    val http = object : HttpEngine {
      override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
        error("Unexpected HEAD")
      override suspend fun download(url: String, range: LongRange?, headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit) {
        onData(if (url.contains("/announce")) Bencode.encode(mapOf("interval" to 3600L,
          "peers" to listOf(mapOf("ip" to "127.0.0.1", "port" to listener.local.port.toLong()))))
        else metainfo)
      }
      override fun close() = Unit
    }

    suspend fun start(scope: CoroutineScope) {
      listener = network.listen(PeerEndpoint("127.0.0.1", 0))
      job = scope.launch {
        while (true) {
          val connection = listener.accept()
          launch {
            try {
              PeerIdentityHandshake(document.identity, extensions = true).respond(connection,
                torrentRandomBytes(20).toByteString(), TorrentBufferBudget(65_536))
              val wire = PeerWire(connection, pieceCount = blocks.size + 1)
              wire.send(PeerMessage.Extended(0, Bencode.encode(mapOf(
                "m" to mapOf("ut_metadata" to PeerExtensions.METADATA.toLong()),
                "metadata_size" to document.info.rawInfo.size.toLong()))))
              wire.send(PeerMessage.Bitfield(pieceBitfield(BooleanArray(blocks.size + 1) { true })))
              wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
              while (true) {
                when (val message = wire.read()) {
                  is PeerMessage.Extended -> if (message.id == PeerExtensions.METADATA) {
                    val request = Bencode.parse(message.payload)
                    if (request["msg_type"]?.integer == 0L) {
                      val piece = requireNotNull(request["piece"]?.integer).toInt()
                      val raw = document.info.rawInfo.toByteArray()
                      val start = piece * 16_384
                      val header = Bencode.encode(mapOf("msg_type" to 1L, "piece" to piece.toLong(),
                        "total_size" to raw.size.toLong()))
                      wire.send(PeerMessage.Extended(PeerExtensions.METADATA, header +
                        raw.copyOfRange(start, minOf(start + 16_384, raw.size))))
                    }
                  }
                  is PeerMessage.Request -> {
                    if (message.index > 0) remaining.await()
                    val block = if (message.index == blocks.size) byteArrayOf(7)
                      else blocks[message.index]
                    wire.send(PeerMessage.Piece(message.index, message.begin,
                      block.copyOfRange(message.begin, message.begin + message.length)))
                  }
                  is PeerMessage.Unknown -> {
                    val request = PeerHashWire.decode(message) as? PeerHashMessage.Request
                      ?: continue
                    hashRequests++
                    requestedHashes.complete(Unit)
                    val proof = leaves.fold(ByteArray(0)) { a, b -> a + b }
                    if (corruptProof) proof[0] = (proof[0].toInt() xor 1).toByte()
                    wire.send(PeerHashWire.encode(PeerHashMessage.Hashes(request.selector,
                      proof.toByteString())))
                  }
                  else -> Unit
                }
              }
            } catch (_: okio.IOException) { /* Client closes when pausing or completing. */
            } finally { connection.close() }
          }
        }
      }
    }

    suspend fun close() = withContext(NonCancellable) {
      job.cancelAndJoin()
      listener.close()
      network.close()
    }
  }
}
