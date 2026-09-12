package com.linroid.ketch.torrent

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
    spec.selected.size * 64L + 128 * 1024
}
