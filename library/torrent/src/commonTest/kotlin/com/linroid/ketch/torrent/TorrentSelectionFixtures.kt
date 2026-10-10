package com.linroid.ketch.torrent

import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import com.linroid.ketch.core.task.TaskRecord
import com.linroid.ketch.core.task.TaskStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import okio.Path
import kotlin.concurrent.atomics.AtomicInt
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.concurrent.atomics.incrementAndFetch
import kotlin.test.assertEquals
import kotlin.test.fail

/**
 * A v1 torrent of files of [sizes] bytes named `f0`, `f1`, … under `pack`, in pieces of
 * [pieceLength]. The default sizes end no file on a piece boundary, so pieces span files.
 */
internal class MultiFileTorrent(
  val sizes: List<Int> = listOf(100_000, 70_000, 86_144),
  val pieceLength: Int = 16_384,
  announce: String? = null,
) {
  val payloads: List<ByteArray> = sizes.mapIndexed { file, size ->
    ByteArray(size) { (it * 31 + file * 7 + it / 977).toByte() }
  }
  private val content = ByteArray(sizes.sum()).also { bytes ->
    var offset = 0
    for (payload in payloads) {
      payload.copyInto(bytes, offset)
      offset += payload.size
    }
  }
  val pieceCount: Int = (content.size + pieceLength - 1) / pieceLength
  val metainfo: ByteArray = Bencode.encode(buildMap {
    announce?.let { put("announce", it) }
    put("info", mapOf("name" to "pack", "piece length" to pieceLength.toLong(),
      "pieces" to (0 until pieceCount).fold(ByteArray(0)) { hashes, index ->
        hashes + sha1Digest(piece(index))
      },
      "files" to sizes.mapIndexed { index, size ->
        mapOf("length" to size.toLong(), "path" to listOf("f$index"))
      }))
  })
  val metadata: TorrentMetadata = TorrentMetadata.fromBencode(metainfo)

  fun piece(index: Int): ByteArray =
    content.copyOfRange(index * pieceLength, minOf((index + 1) * pieceLength, content.size))

  /** The pieces holding any byte of [file]. */
  fun piecesOf(file: Int): IntRange {
    val start = sizes.take(file).sum()
    return start / pieceLength..(start + sizes[file] - 1) / pieceLength
  }

  /** The total size of [files]. */
  fun sizeOf(vararg files: Int): Long = files.sumOf { sizes[it].toLong() }

  /** Writes every file into [directory], as a seeder's storage. */
  fun writeTo(directory: Path) {
    torrentFileSystem.createDirectories(directory)
    payloads.forEachIndexed { index, payload ->
      torrentFileSystem.write(directory / "f$index") { write(payload) }
    }
  }
}

/**
 * A hand-written loopback seeder of [torrent] that serves each piece once its gate opens, so a
 * test decides how far a download gets. Requests on a connection are served in order. Its
 * [http] engine serves the metainfo at any URL and the seeder at [ANNOUNCE].
 */
@OptIn(ExperimentalAtomicApi::class)
internal class GatedSeeder(val torrent: MultiFileTorrent = MultiFileTorrent(announce = ANNOUNCE)) {
  private val gates = List(torrent.pieceCount) { CompletableDeferred<Unit>() }

  /** Connections accepted from downloaders. */
  val accepted = AtomicInt(0)
  private val network = createTorrentNetwork()
  private lateinit var listener: TorrentListener
  private lateinit var job: Job

  val endpoint: PeerEndpoint get() = listener.local

  fun open(pieces: IntRange) = pieces.forEach { gates[it].complete(Unit) }

  fun openAll() = open(0 until torrent.pieceCount)

  val http: HttpEngine = object : HttpEngine {
    override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
      error("Unexpected HEAD")

    override suspend fun download(
      url: String,
      range: LongRange?,
      headers: Map<String, String>,
      onData: suspend (ByteArray) -> Unit,
    ) {
      onData(if (url == ANNOUNCE || url.startsWith("$ANNOUNCE?")) {
        Bencode.encode(mapOf("interval" to 3600L, "peers" to listOf(
          mapOf("ip" to "127.0.0.1", "port" to listener.local.port.toLong()))))
      } else torrent.metainfo)
    }

    override fun close() = Unit
  }

  suspend fun start(scope: CoroutineScope) {
    listener = network.listen(PeerEndpoint("127.0.0.1", 0))
    job = scope.launch {
      while (isActive) {
        val connection = listener.accept()
        accepted.incrementAndFetch()
        launch { serve(connection) }
      }
    }
  }

  private suspend fun serve(connection: TorrentConnection) {
    try {
      val wire = PeerWire(connection, torrent.metadata)
      wire.handshake(PeerHandshake(torrent.metadata.infoHash, torrentRandomBytes(20), false,
        false))
      wire.send(PeerMessage.Bitfield(pieceBitfield(BooleanArray(torrent.pieceCount) { true })))
      wire.send(PeerMessage.Control(PeerMessage.Signal.UNCHOKE))
      while (true) {
        val message = wire.read()
        if (message is PeerMessage.Request) {
          gates[message.index].await()
          wire.send(PeerMessage.Piece(message.index, message.begin,
            torrent.piece(message.index).copyOfRange(message.begin,
              message.begin + message.length)))
        }
      }
    } catch (e: CancellationException) {
      throw e
    } catch (_: Exception) {
      // The downloader closed the connection.
    } finally {
      connection.close()
    }
  }

  suspend fun close() = withContext(NonCancellable) {
    job.cancelAndJoin()
    listener.close()
    network.close()
  }

  companion object {
    const val URL = "http://fixture/pack.torrent"
    const val ANNOUNCE = "http://fixture/announce"
  }
}

/** Counts the peer connections an engine opens. */
@OptIn(ExperimentalAtomicApi::class)
internal class CountingTorrentNetwork(
  private val delegate: TorrentNetwork = createTorrentNetwork(),
) : TorrentNetwork by delegate {
  val connects = AtomicInt(0)

  override suspend fun connect(remote: PeerEndpoint): TorrentConnection =
    delegate.connect(remote).also { connects.incrementAndFetch() }
}

/** A [TaskStore] in a JSON file, so a test can open the same tasks in another [Ketch]. */
internal class FileTaskStore(private val path: Path) : TaskStore {
  private val mutex = Mutex()

  private fun read(): List<TaskRecord> = if (torrentFileSystem.exists(path)) {
    Json.decodeFromString(torrentFileSystem.read(path) { readUtf8() })
  } else emptyList()

  private fun write(records: List<TaskRecord>) =
    torrentFileSystem.write(path) { writeUtf8(Json.encodeToString(records)) }

  override suspend fun save(record: TaskRecord): Unit = mutex.withLock {
    write(read().filter { it.taskId != record.taskId } + record)
  }

  override suspend fun load(taskId: String): TaskRecord? = mutex.withLock {
    read().firstOrNull { it.taskId == taskId }
  }

  override suspend fun loadAll(): List<TaskRecord> = mutex.withLock { read() }

  override suspend fun remove(taskId: String): Unit = mutex.withLock {
    write(read().filter { it.taskId != taskId })
  }
}

/** A [TaskStore] in memory, for records a test writes before Ketch starts. */
internal class MemoryTaskStore : TaskStore {
  private val mutex = Mutex()
  private val records = linkedMapOf<String, TaskRecord>()

  override suspend fun save(record: TaskRecord): Unit = mutex.withLock {
    records[record.taskId] = record
  }

  override suspend fun load(taskId: String): TaskRecord? = mutex.withLock { records[taskId] }

  override suspend fun loadAll(): List<TaskRecord> = mutex.withLock { records.values.toList() }

  override suspend fun remove(taskId: String) {
    mutex.withLock { records.remove(taskId) }
  }
}

/** Waits for [block], naming the [stage] and [task]'s progress when it takes too long. */
internal suspend fun <T : Any> awaitStage(
  stage: String,
  task: DownloadTask? = null,
  timeoutMs: Long = 10_000,
  block: suspend () -> T,
): T = withTimeoutOrNull(timeoutMs) { block() } ?: fail(buildString {
  append("Timed out after $timeoutMs ms waiting for $stage")
  if (task != null) {
    append(": state=${task.state.value}, ")
    append("downloaded=${task.segments.value.sumOf { it.downloadedBytes }} bytes")
  }
})

/** Polls [condition] in real time; the source's work runs on its own dispatchers. */
internal suspend fun eventually(stage: String, condition: suspend () -> Boolean) {
  awaitStage(stage) {
    while (!condition()) delay(10)
    true
  }
}

/** The source left no reserved task and no engine slot behind. */
internal suspend fun TorrentDownloadSource.assertNoLeaks() {
  eventually("the source to release every task") { reservedTasks() == 0 && slotsInUse() == 0 }
  assertEquals(0, reservedTasks())
  assertEquals(0, slotsInUse())
}

/** The engine returned everything it admitted; call after it stopped. */
internal fun KotlinTorrentEngine.assertNoLeaks() {
  assertEquals(0, admittedSessionBytes)
  assertEquals(0, allocatedExchangeBytes)
}
