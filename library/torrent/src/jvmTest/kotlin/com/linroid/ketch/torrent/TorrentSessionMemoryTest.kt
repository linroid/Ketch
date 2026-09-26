package com.linroid.ketch.torrent

import com.linroid.ketch.core.engine.HttpEngine
import com.linroid.ketch.core.engine.ServerInfo
import java.lang.management.ManagementFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import okio.ByteString.Companion.toByteString
import okio.FileSystem
import okio.Path
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * Measures retained JVM heap of live torrent sessions against what session admission charges.
 * Runs only with KETCH_TORRENT_MEMORY=1; heap figures need an otherwise idle test JVM.
 *
 * Each case holds [SESSIONS] distinct torrents in DOWNLOADING with no peers, so the figure is
 * session state only: metadata, storage and scheduler indexes, tracker and discovery state.
 * Piece and wire buffers are charged to the transfer budget and are not part of it.
 */
class TorrentSessionMemoryTest {
  private data class Case(val format: String, val pieces: Int, val files: Int)

  private data class Result(val case: Case, val charged: Long, val retained: Long)

  @Test
  fun sessionAdmission_boundsRetainedHeap() = runBlocking(Dispatchers.Default) {
    withTimeout(30.minutes) {
      val results = listOf(
        Case("v1", 1_000, 1), Case("v1", 30_000, 1), Case("v1", 30_000, 1_000),
        Case("v1", 100_000, 1), Case("v1", 100_000, 10_000),
        Case("v2", 1_000, 1), Case("v2", 30_000, 1), Case("v2", 30_000, 1_000),
        Case("v2", 100_000, 1), Case("v2", 100_000, 10_000),
      ).map { case -> if (case.format == "v1") measureV1(case) else measureV2(case) }
      val report = buildString {
        appendLine("| Format | Pieces | Files | Charged / session | Retained / session | Ratio |")
        appendLine("| --- | ---: | ---: | ---: | ---: | ---: |")
        for (result in results) {
          val charged = result.charged / SESSIONS
          val retained = result.retained / SESSIONS
          appendLine("| ${result.case.format} | ${result.case.pieces} | ${result.case.files} | " +
            "${kib(charged)} | ${kib(retained)} | " +
            "${"%.2f".format(charged.toDouble() / retained)} |")
        }
      }
      println(report)
      System.getenv("KETCH_TORRENT_MEMORY_REPORT")?.takeIf { it.isNotBlank() }?.let {
        java.io.File(it).writeText(report)
      }
      for (result in results) {
        assertTrue(result.charged >= result.retained,
          "${result.case} charged ${result.charged} but retained ${result.retained}")
      }
    }
  }

  private suspend fun measureV1(case: Case): Result = withRoot(case) { root, http ->
    val engine = KotlinTorrentEngine(config(), http = http)
    try {
      engine.start()
      val baseline = usedHeap()
      val sessions = (0 until SESSIONS).map { index ->
        val metadata = v1Metadata("v1-${case.pieces}-${case.files}-$index", case)
        engine.addTask(TorrentTaskSpec("v1-$index", metadata,
          (root / metadata.name).toString(), emptySet()))
      }
      sessions.forEach { it.resume() }
      sessions.forEach { session ->
        session.state.first { it == TorrentSessionState.DOWNLOADING }
      }
      val retained = usedHeap() - baseline
      Result(case, engine.admittedSessionBytes.toLong(), retained)
    } finally {
      engine.stop()
    }
  }

  private suspend fun measureV2(case: Case): Result = withRoot(case) { root, _ ->
    val engine = KotlinTorrentEngine(config())
    try {
      engine.start()
      // Parsed documents are session state too: admission charges their info and layers.
      val baseline = usedHeap()
      val documents = (0 until SESSIONS).map { v2Document("v2-${case.pieces}-$it", case) }
      coroutineScope {
        val ready = documents.map { CompletableDeferred<Unit>() }
        val jobs = documents.mapIndexed { index, document ->
          launch {
            engine.withV2Download("v2-$index", document, (root / "v2-$index").toString(),
              discover = { awaitCancellation() }) { session ->
              session.resume()
              session.state.first { it == TorrentSessionState.DOWNLOADING }
              ready[index].complete(Unit)
              awaitCancellation()
            }
          }
        }
        ready.forEach { it.await() }
        val retained = usedHeap() - baseline
        val charged = engine.admittedSessionBytes.toLong()
        jobs.forEach { it.cancelAndJoin() }
        Result(case, charged, retained)
      }
    } finally {
      engine.stop()
    }
  }

  private suspend fun <T> withRoot(
    case: Case,
    block: suspend (Path, TorrentHttp) -> T,
  ): T {
    val root = FileSystem.SYSTEM_TEMPORARY_DIRECTORY /
      "ketch-memory-${case.format}-${InfoHash.fromBytes(torrentRandomBytes(20)).hex}"
    FileSystem.SYSTEM.createDirectories(root)
    // Answers every announce with no peers so discovery, and the swarm, stay open.
    val http = TorrentHttp(object : HttpEngine {
      override suspend fun head(url: String, headers: Map<String, String>): ServerInfo =
        error("Unused")
      override suspend fun download(
        url: String,
        range: LongRange?,
        headers: Map<String, String>,
        onData: suspend (ByteArray) -> Unit,
      ) = onData(Bencode.encode(mapOf("interval" to 3600L, "peers" to ByteArray(0))))
      override fun close() = Unit
    })
    try {
      return block(root, http)
    } finally {
      FileSystem.SYSTEM.deleteRecursively(root, mustExist = false)
    }
  }

  private fun config() = TorrentConfig(dhtEnabled = false, maxActiveTorrents = SESSIONS,
    maxSessionStateBytes = 1024 * 1024 * 1024, maxExchangeBytes = Int.MAX_VALUE)

  private fun v1Metadata(name: String, case: Case): TorrentMetadata {
    val perFile = case.pieces / case.files * PIECE_LENGTH
    val info = if (case.files == 1) {
      mapOf("name" to name, "length" to perFile, "piece length" to PIECE_LENGTH,
        "pieces" to torrentRandomBytes(case.pieces * 20))
    } else {
      mapOf("name" to name, "piece length" to PIECE_LENGTH,
        "pieces" to torrentRandomBytes(case.pieces * 20),
        "files" to (0 until case.files).map { index ->
          mapOf("length" to perFile, "path" to listOf("dir-${index / 100}", "file-$index.bin"))
        })
    }
    return TorrentMetadata.fromBencode(Bencode.encode(mapOf(
      "announce" to "http://tracker.invalid/announce", "info" to info,
    ), maxBytes = 64 * 1024 * 1024))
  }

  private fun v2Document(name: String, case: Case): TorrentV2Document {
    val perFile = case.pieces / case.files
    val layers = linkedMapOf<okio.ByteString, ByteArray>()
    val tree = linkedMapOf<String, MutableMap<String, Any>>()
    for (index in 0 until case.files) {
      tree.getOrPut("dir-${index / 100}") { linkedMapOf() }["file-$index.bin"] =
        fileEntry(perFile, layers)
    }
    val bytes = Bencode.encode(mapOf(
      "info" to mapOf("name" to name, "meta version" to 2L, "piece length" to PIECE_LENGTH,
        "file tree" to tree),
      "piece layers" to layers,
    ), maxBytes = 64 * 1024 * 1024)
    return TorrentV2Document.parse(bytes, maxDocumentBytes = 32 * 1024 * 1024,
      maxInfoBytes = 4 * 1024 * 1024, maxFiles = 100_000, maxNodes = 1_000_000,
      maxLayerBytes = 64L * 1024 * 1024)
  }

  /** Random piece hashes stand in for payload; only their Merkle relation to the root matters. */
  private fun fileEntry(perFile: Int, layers: MutableMap<okio.ByteString, ByteArray>): Any {
    val layer = torrentRandomBytes(perFile * 32)
    val frontier = TorrentMerkleFrontier(baseLayer = PIECE_LAYER)
    for (offset in layer.indices step 32) frontier.append(layer.copyOfRange(offset, offset + 32))
    val root = frontier.digest()
    if (perFile > 1) layers[root.toByteString()] = layer
    return mapOf("" to mapOf("length" to perFile * PIECE_LENGTH, "pieces root" to root))
  }

  private fun usedHeap(): Long {
    val memory = ManagementFactory.getMemoryMXBean()
    var previous = Long.MAX_VALUE
    // Collect until the heap stops shrinking so finalizable and soft garbage is gone.
    repeat(10) {
      System.gc()
      Thread.sleep(100)
      val used = memory.heapMemoryUsage.used
      if (previous - used < 64 * 1024) return used
      previous = used
    }
    return memory.heapMemoryUsage.used
  }

  private fun kib(bytes: Long) = "%,d KiB".format(bytes / 1024)

  private companion object {
    const val SESSIONS = 5
    const val PIECE_LENGTH = 256L * 1024
    /** 256 KiB pieces sit four levels above the 16 KiB block hashes. */
    const val PIECE_LAYER = 4
  }
}
