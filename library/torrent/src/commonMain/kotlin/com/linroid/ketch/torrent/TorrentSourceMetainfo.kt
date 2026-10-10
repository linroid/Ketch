package com.linroid.ketch.torrent

import com.linroid.ketch.api.FileSelectionMode
import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.Segment
import com.linroid.ketch.api.SourceFile
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okio.ByteString.Companion.toByteString
import okio.Path

/** Validates the format before dispatch so hybrid content never falls back to v1 parsing. */
internal fun resolveV2Metainfo(
  url: String?,
  bytes: ByteArray,
  config: TorrentConfig,
  privacy: TorrentDiscoveryPrivacy,
): ResolvedSource? {
  val envelope = Bencode.parse(bytes, config.maxMetadataBytes)
  val version = envelope["info"]?.get("meta version") ?: return null
  require(version.integer == 2L) { "Unsupported torrent metainfo version" }
  val document = TorrentV2Document.parse(bytes, maxDocumentBytes = config.maxMetadataBytes,
    maxFiles = config.maxFilesPerTorrent)
  sourceTrackerTiers(bytes, config.maxMetadataBytes)
  val mapping = TorrentOutputMapping.from(document)
  val hash = document.info.hash.hex
  val name = document.info.displayName?.utf8()?.takeIf { value ->
    try { TorrentMetadata.validatePathComponent(value); true } catch (_: Exception) { false }
  } ?: hash
  return ResolvedSource(
    url = url ?: "torrent:$hash", sourceType = TorrentDownloadSource.TYPE,
    totalBytes = document.info.totalBytes, supportsResume = true, suggestedFileName = name,
    maxSegments = mapping.files.size,
    metadata = mapOf("infoHash" to hash, "metainfo" to encodeBase64(bytes), "format" to "v2",
      "name" to name, "pieceLength" to document.info.pieceLength.toString(),
      "discoveryPrivacy" to privacy.name),
    files = mapping.files.map { file ->
      val path = file.components.joinToString("/")
      SourceFile(file.id, path, document.info.files[file.v2Index].length,
        metadata = mapOf("path" to path))
    },
    selectionMode = FileSelectionMode.MULTIPLE,
  )
}

internal fun sourceTrackerTiers(bytes: ByteArray, maxBytes: Int): List<List<String>> {
  val envelope = Bencode.parse(bytes, maxBytes)
  val tiers = envelope["announce-list"]?.let { node ->
    requireNotNull(node.list) { "Invalid tracker tiers" }.map { tier ->
      requireNotNull(tier.list) { "Invalid tracker tier" }.map { tracker ->
        requireNotNull(tracker.text()) { "Invalid tracker URL" }
      }
    }
  } ?: envelope["announce"]?.text()?.let { listOf(listOf(it)) }.orEmpty()
  return TrackerConfiguration.prepare(tiers).tiers
}

/** Stable task-bound journal lives beside the payload, including without a configured state dir. */
internal fun v2CreationLog(output: Path, taskId: String): Path =
  checkNotNull(output.parent) / ".ketch-v2-$taskId.creation"

/**
 * One content file of a torrent as a selection names it: [id] is a metainfo index (v1) or an
 * output mapping ID (v2 and hybrid), and [components] its path under the task's output folder.
 */
internal class TorrentFileRow(
  val id: String,
  val path: String,
  val size: Long,
  val components: List<String>,
)

/** Every content file of a torrent in metainfo order, from metadata the source holds. */
internal class TorrentFileTable(
  val infoHash: String,
  /** Whether the task runs as v2 (pure v2 or hybrid). */
  val v2: Boolean,
  val files: List<TorrentFileRow>,
) {
  val ids: Set<String> = files.mapTo(LinkedHashSet()) { it.id }
  private val byId = files.associateBy { it.id }

  operator fun get(id: String): TorrentFileRow? = byId[id]

  /** [ids] in metainfo order. */
  fun ordered(ids: Set<String>): Set<String> =
    files.filter { it.id in ids }.mapTo(LinkedHashSet()) { it.id }

  fun totalOf(ids: Set<String>): Long = files.filter { it.id in ids }.sumOf { it.size }

  /**
   * One segment per file of [ids], in metainfo order at cumulative offsets and indexed by file
   * index, holding the verified bytes [progress] reports for it.
   */
  fun segments(ids: Set<String>, progress: (TorrentFileRow) -> Long): List<Segment> {
    var offset = 0L
    return files.filter { it.id in ids }.map { file ->
      Segment(file.id.toInt(), offset, offset + file.size - 1,
        progress(file).coerceIn(0, file.size)).also { offset += file.size }
    }
  }

  companion object {
    /** Parses [bytes] as v2 or hybrid metainfo first, then as v1. */
    fun parse(bytes: ByteArray, config: TorrentConfig): TorrentFileTable {
      resolveV2Metainfo(null, bytes, config, config.discoveryPrivacy)?.let { resolved ->
        return TorrentFileTable(requireNotNull(resolved.metadata["infoHash"]), v2 = true,
          resolved.files.map { TorrentFileRow(it.id, it.name, it.size, it.name.split('/')) })
      }
      val metadata = TorrentMetadata.fromBencode(bytes, config.maxMetadataBytes)
      return TorrentFileTable(metadata.infoHash.hex, v2 = false, metadata.files.map {
        TorrentFileRow(it.index.toString(), it.path, it.size, it.path.split('/'))
      })
    }
  }
}

/**
 * The most recently used [TorrentFileTable]s, keyed by their metainfo's digest: at most
 * [capacity] tables and, beyond the newest, [maxRows] files in all.
 */
internal class TorrentFileTables(
  private val capacity: Int = 8,
  private val maxRows: Int = 100_000,
) {
  private val mutex = Mutex()
  private val tables = linkedMapOf<String, TorrentFileTable>()

  /** The table of [bytes], parsed with [parse] outside the lock when it is not cached. */
  suspend fun get(bytes: ByteArray, parse: () -> TorrentFileTable): TorrentFileTable {
    val key = sha1Digest(bytes).toByteString().hex()
    mutex.withLock {
      tables.remove(key)?.let { cached ->
        tables[key] = cached
        return cached
      }
    }
    val table = parse()
    mutex.withLock {
      tables.remove(key)
      tables[key] = table
      var rows = tables.values.sumOf { it.files.size }
      while (tables.size > capacity || rows > maxRows && tables.size > 1) {
        val oldest = tables.keys.first()
        rows -= checkNotNull(tables.remove(oldest)).files.size
      }
    }
    return table
  }

  /** How many tables are cached; for tests. */
  suspend fun size(): Int = mutex.withLock { tables.size }
}
