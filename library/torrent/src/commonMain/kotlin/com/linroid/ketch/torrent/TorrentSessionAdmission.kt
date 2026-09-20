package com.linroid.ketch.torrent

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** Apply every limit together before storage construction or checkpoint decoding allocates indexes. */
internal fun admitSession(
  spec: TorrentTaskSpec,
  config: TorrentConfig,
  budget: TorrentBufferBudget,
): TorrentBufferBudget.Lease {
  require(spec.metadata.files.size <= config.maxFilesPerTorrent) { "Torrent file limit exceeded" }
  require(spec.metadata.pieceHashes.size / 20 <= config.maxPiecesPerTorrent) {
    "Torrent piece limit exceeded"
  }
  val bytes = sessionStateWeight(spec)
  check(bytes <= budget.capacity) { "Torrent session state exceeds admission capacity" }
  return checkNotNull(budget.reserve(bytes.toInt())) { "Torrent session state budget exhausted" }
}

/**
 * Conservative allowance for retained metadata, storage/scheduler arrays and boxed candidate lists,
 * path/ownership indexes, two checking buffers, and checkpoint decoding. Peer bitfields and wire
 * buffers are already charged by TorrentSwarm. This is admission accounting, not measured heap/RSS.
 * Keep arithmetic in Long; an over-capacity estimate must fail before narrowing to an Int.
 */
internal fun sessionStateWeight(spec: TorrentTaskSpec): Long {
  val metadata = spec.metadata
  val pieces = metadata.pieceHashes.size.toLong() / 20
  val largestPiece = minOf(metadata.pieceLength, metadata.totalBytes)
  require(largestPiece >= 0)
  return cacheWeight(metadata) + metadata.metainfoBytes.size +
    metadata.trackers.sumOf { it.length * 4L } +
    (metadata.comment?.length ?: 0) * 4L + (metadata.createdBy?.length ?: 0) * 4L +
    pieces * 128 + metadata.files.sumOf { 512 + it.path.length * 4L } +
    largestPiece * 2 + (spec.resumeData?.size ?: 0) * 8L +
    spec.outputPath.length * 4L + (spec.magnetUri?.length ?: 0) * 4L +
    spec.selected.size * 64L + 128 * 1024 + trackerControlStateWeight()
}

/**
 * Dedicated control pool is backed by the held session lease. Reserve the 256-entry edit ceiling
 * even for empty metainfo lists, so a later admitted edit can always start its control worker.
 */
internal fun trackerControlStateWeight(trackers: Int = 256): Long {
  require(trackers in 0..256)
  // Includes announce diagnostics, scrape estimates, cooldown entries, and snapshot references.
  return (trackers.toLong() + 1) * 384 + 8192
}


/** Runtime ownership ledger; a stopped-but-registered session remains charged after cleanup failure. */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentAdmissionLedger(private val budget: TorrentBufferBudget) {
  private val entries = AtomicReference<List<TorrentBufferBudget.Lease>?>(emptyList())

  fun admit(spec: TorrentTaskSpec, config: TorrentConfig): TorrentBufferBudget.Lease {
    val lease = admitSession(spec, config, budget)
    while (true) {
      val current = entries.load()
      if (current == null) {
        lease.close()
        error("Torrent admission is closed")
      }
      if (entries.compareAndSet(current, current + lease)) return lease
    }
  }

  fun release(lease: TorrentBufferBudget.Lease) {
    while (true) {
      val current = entries.load() ?: break
      if (lease !in current || entries.compareAndSet(current, current.filter { it !== lease })) break
    }
    lease.close()
  }

  /** Called only after the owning runtime's jobs have finished. No new admission is possible. */
  fun close() {
    entries.exchange(null)?.forEach { it.close() }
  }
}

/** Admit retained full v2 content plus storage/layout indexes before constructing their arrays. */
internal fun admitV2Session(
  document: TorrentV2Document,
  selectedCount: Int,
  outputLength: Int,
  config: TorrentConfig,
  budget: TorrentBufferBudget,
  checkpoint: TorrentV2Checkpoint? = null,
): TorrentBufferBudget.Lease {
  require(document.info.rawInfo.size <= config.maxMetadataBytes) { "Torrent info limit exceeded" }
  require(outputLength >= 0)
  require(document.info.files.size <= config.maxFilesPerTorrent &&
    selectedCount in 0..config.maxFilesPerTorrent) { "Torrent file limit exceeded" }
  require(document.info.pieceLength <= 16 * 1024 * 1024) { "Torrent piece length limit exceeded" }
  var pieces = 0L
  for (file in document.info.files) {
    val count = file.length / document.info.pieceLength +
      if (file.length % document.info.pieceLength == 0L) 0 else 1
    require(count <= config.maxPiecesPerTorrent - pieces) { "Torrent piece limit exceeded" }
    pieces += count
  }
  val recoveryBytes = checkpoint?.let { saved ->
    4096L + saved.output.length * 4L + saved.verifiedHint.size * 2L +
      saved.selected.sumOf { it.length * 4L + 128 } +
      saved.owned.sumOf { claim ->
        512L + claim.identity.length * 4L +
          claim.components.sumOf { it.length * 4L + 128 }
      }
  } ?: 0L
  val bytes = recoveryBytes + document.info.rawInfo.size * 4L +
    document.pieceLayers.values.sumOf { it.size * 2L + 128 } +
    document.info.files.sumOf { file -> 2048L + file.path.sumOf { it.size * 8L + 128 } } +
    pieces * 128 + selectedCount * 128L + outputLength * 4L + 256 * 1024
  check(bytes <= budget.capacity) { "Torrent session state exceeds admission capacity" }
  return checkNotNull(budget.reserve(bytes.toInt())) { "Torrent session state budget exhausted" }
}
