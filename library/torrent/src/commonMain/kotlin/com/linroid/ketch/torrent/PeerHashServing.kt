package com.linroid.ketch.torrent

import com.linroid.ketch.api.log.KetchLogger
import okio.Buffer
import okio.ByteString
import okio.ByteString.Companion.toByteString

private val zeroHashes: List<ByteString> = buildList {
  var hash = ByteArray(32)
  repeat(64) {
    add(hash.toByteString())
    hash = Sha256().update(hash).update(hash).digest()
  }
}

/**
 * The root of a BEP 52 subtree [level] levels above 16 KiB leaves that holds only padding: its
 * leaves are zero hashes, not hashes of zero bytes. Memoized for levels 0..63.
 */
internal fun merkleZeroHash(level: Int): ByteArray {
  require(level >= 0)
  if (level < zeroHashes.size) return zeroHashes[level].toByteArray()
  var hash = zeroHashes.last().toByteArray()
  repeat(level - zeroHashes.lastIndex) { hash = Sha256().update(hash).update(hash).digest() }
  return hash
}

/**
 * What serving one hash request needs to know about its file: [height] is the root's level
 * above 16 KiB leaves, [pieceLayer] the piece layer's, and [firstPiece] the file's first global
 * piece. [blockPath] proofs come from one verified piece's block hashes; the others come from
 * the metainfo's piece layer alone.
 */
internal class HashServeBounds(
  val fileLength: Long,
  val height: Int,
  val pieceLayer: Int,
  val hasPieceLayer: Boolean,
  val firstPiece: Long,
  val blockPath: Boolean,
) {
  /** 16 KiB leaves that hold file bytes; leaves from here on are padding. */
  val blocks: Long = (fileLength - 1) / TorrentMerkleRoot.BLOCK_BYTES + 1

  /** The global piece whose block hashes a [blockPath] request for [selector] is answered from. */
  fun piece(selector: PeerHashSelector): Long =
    firstPiece + ((selector.index shl selector.baseLayer) shr minOf(pieceLayer, height))
}

/**
 * Checks [selector] against the authenticated document and returns what serving it needs, or
 * null when it must be rejected: an unknown root, a base at or above the root, a range past the
 * tree's width, more proof layers than lie below the root, block hashes that would span more
 * than one piece or only padding, more than [PeerHashSelector.MAX_HASHES] hashes above the
 * piece layer, or an answer longer than one frame.
 */
internal fun peerHashServeBounds(
  document: TorrentV2Document,
  layout: TorrentContentLayout,
  selector: PeerHashSelector,
): HashServeBounds? {
  require(layout.infoHash == document.info.hash) { "Layout belongs to another torrent" }
  val fileIndex = document.info.files.indexOfFirst {
    it.length > 0 && it.piecesRoot == selector.root
  }
  if (fileIndex < 0) return null
  val length = document.info.files[fileIndex].length
  val blocks = (length - 1) / TorrentMerkleRoot.BLOCK_BYTES + 1
  var height = 0
  while ((1L shl height) < blocks) height++
  val base = selector.baseLayer
  if (base >= height || selector.proofLayers > height - base - 1) return null
  // The answer is one hash message: the selector, then every hash.
  if (48 + selector.hashCount * 32L > PeerWire.MAX_FRAME_SIZE - 1) return null
  if (selector.index + selector.length > (1L shl (height - base))) return null
  val pieceLayer = (layout.pieceLength / TorrentMerkleRoot.BLOCK_BYTES).countTrailingZeroBits()
  val hasPieceLayer = length > layout.pieceLength
  val blockPath = base < pieceLayer || !hasPieceLayer
  // Upper levels are folded from layer hashes per answer, so their count stays BEP 52's.
  if (!blockPath && selector.length > PeerHashSelector.MAX_HASHES) return null
  if (blockPath) {
    // Hashes below the piece layer come from one piece read back from storage, never several.
    val span = minOf(pieceLayer, height)
    val first = selector.index shl base
    val end = minOf((selector.index + selector.length) shl base, blocks)
    if (first >= blocks || first shr span != (end - 1) shr span) return null
  }
  val offset = layout.files.single { it.v2Index == fileIndex }.offset
  return HashServeBounds(length, height, pieceLayer, hasPieceLayer, offset / layout.pieceLength,
    blockPath)
}

/**
 * The hashes answering [selector]: its base-layer hashes, then the uncles from the bottom up,
 * exactly the order [verifyPeerHashes] reads them in. [node] supplies every node that holds file
 * bytes; padding nodes are zero hashes.
 */
internal fun peerHashProof(
  selector: PeerHashSelector,
  bounds: HashServeBounds,
  node: (level: Int, index: Long) -> ByteArray,
): ByteString {
  fun value(level: Int, index: Long): ByteArray =
    if (index shl level >= bounds.blocks) merkleZeroHash(level) else node(level, index)
  val output = Buffer()
  for (offset in 0 until selector.length) {
    output.write(value(selector.baseLayer, selector.index + offset))
  }
  val group = selector.length.countTrailingZeroBits()
  var level = selector.baseLayer + group
  var position = selector.index shr group
  repeat(selector.hashCount - selector.length) {
    output.write(value(level, position xor 1L))
    position = position shr 1
    level++
  }
  return output.readByteString()
}

/**
 * Answers BEP 52 hash requests for one owner, from authenticated piece layers and, below them,
 * from pieces read back from storage. Levels from [groupBits] above the piece layer up to the
 * root are folded once per file and cached, at most [maxCacheBytes] together and charged to
 * [state]; levels between are folded on demand from at most 2^([groupBits] - 1) layer hashes.
 * The block hashes of the [maxSubtrees] pieces proved last are kept, also charged to [state], so
 * repeated requests never read a piece again. Reading one back is upload work: [canRead] is
 * asked first and, when it refuses, the request is rejected without reading; a piece that was
 * read pays its bytes to [chargeRead], and a read that fails or never happens pays nothing.
 * One caller at a time (the serve worker).
 */
internal class TorrentV2HashServer(
  private val document: TorrentV2Document,
  private val layout: TorrentContentLayout,
  private val state: TorrentBufferBudget,
  private val maxCacheBytes: Int = 1 shl 20,
  private val groupBits: Int = 9,
  private val maxSubtrees: Int = 4,
  private val canRead: suspend () -> Boolean = { true },
  private val chargeRead: suspend (bytes: Long) -> Unit = {},
) {
  private class Upper(
    /** Nodes from the cache's first level up to the root's, each level's hashes in a row. */
    val levels: List<ByteString>,
    val lease: TorrentBufferBudget.Lease,
  )

  private class Subtree(
    val piece: Long,
    /** Levels 0 until the subtree's top, 2^(top - level) hashes each, padding included. */
    val levels: List<ByteString>,
    val lease: TorrentBufferBudget.Lease,
  )

  private val log = KetchLogger("TorrentSwarm")
  // Least recently used first.
  private val upper = LinkedHashMap<ByteString, Upper>()
  private var cachedBytes = 0
  // Least recently used first, by global piece.
  private val subtrees = LinkedHashMap<Long, Subtree>()
  private var warned = false
  private var closed = false

  init {
    require(layout.infoHash == document.info.hash) { "Layout belongs to another torrent" }
    require(maxCacheBytes > 0 && groupBits in 1..20 && maxSubtrees >= 1)
  }

  /** Bytes the upper-level caches hold; for tests. */
  internal val cacheBytes: Int get() = cachedBytes

  /**
   * The hashes answering [selector], whose [bounds] came from [peerHashServeBounds], or null to
   * reject it: the piece it needs is not readable, or its upper levels cannot be cached.
   */
  suspend fun respond(
    selector: PeerHashSelector,
    bounds: HashServeBounds,
    piece: suspend (Int) -> TorrentV2PieceStore.ReadOutcome,
  ): ByteString? {
    check(!closed) { "Hash server is closed" }
    val base = selector.baseLayer
    val group = selector.length.countTrailingZeroBits()
    val uncles = selector.hashCount - selector.length
    val top = if (uncles == 0) base else base + group + uncles - 1
    val cacheLevel = minOf(bounds.height, bounds.pieceLayer + groupBits)
    val layer = if (bounds.hasPieceLayer) document.pieceLayers[selector.root] else null
    if (bounds.hasPieceLayer && layer == null) return null
    val cached = if (layer != null && top >= cacheLevel) {
      upper(selector.root, bounds, layer, cacheLevel) ?: return null
    } else null
    val blocks = if (bounds.blockPath) {
      subtree(bounds, bounds.piece(selector), minOf(bounds.pieceLayer, bounds.height), piece)
        ?: return null
    } else null
    return peerHashProof(selector, bounds) { level, index ->
      when {
        blocks != null && level < blocks.levels.size -> hash(blocks.levels[level],
          index - ((blocks.piece - bounds.firstPiece) shl (blocks.levels.size - level)))
        layer == null -> error("Hash node above a piece without a layer")
        level == bounds.pieceLayer -> hash(layer, index)
        cached != null && level >= cacheLevel -> hash(cached.levels[level - cacheLevel], index)
        else -> fold(layer, bounds.pieceLayer, level, index)
      }
    }
  }

  /** Releases every cached hash. */
  fun close() {
    closed = true
    upper.values.forEach { it.lease.close() }
    upper.clear()
    cachedBytes = 0
    subtrees.values.forEach { it.lease.close() }
    subtrees.clear()
  }

  private fun hash(row: ByteString, index: Long): ByteArray {
    val offset = index * 32
    check(index >= 0 && offset < row.size) { "Hash node outside its level" }
    return row.substring(offset.toInt(), offset.toInt() + 32).toByteArray()
  }

  /** The node at [level] over layer hashes at [from], padded with zero subtrees. */
  private fun fold(layer: ByteString, from: Int, level: Int, index: Long): ByteArray {
    val count = layer.size / 32L
    val start = index shl (level - from)
    val end = minOf((index + 1) shl (level - from), count)
    var row = (start until end).map { hash(layer, it) }
    for (current in from until level) row = pairs(row, current)
    return row.single()
  }

  private fun pairs(row: List<ByteArray>, level: Int): List<ByteArray> =
    row.chunked(2) { Sha256().update(it[0]).update(it.getOrNull(1) ?: merkleZeroHash(level))
      .digest() }

  /** The file's cached levels from [from] to its root, built and admitted on first use. */
  private fun upper(
    root: ByteString,
    bounds: HashServeBounds,
    layer: ByteString,
    from: Int,
  ): Upper? {
    upper.remove(root)?.let { cache ->
      upper[root] = cache
      return cache
    }
    val pieces = layer.size / 32L
    val sizes = (from..bounds.height).map { level ->
      ((pieces - 1) shr (level - bounds.pieceLayer)) + 1
    }
    val bytes = sizes.sumOf { it * 32 } + 256
    if (bytes > maxCacheBytes) return null
    while (upper.isNotEmpty() && cachedBytes + bytes > maxCacheBytes) evictOldest()
    var lease = state.reserve(bytes.toInt())
    while (lease == null && upper.isNotEmpty()) {
      evictOldest()
      lease = state.reserve(bytes.toInt())
    }
    if (lease == null) return null
    try {
      val levels = mutableListOf<ByteString>()
      var row = (0 until sizes.first()).map { fold(layer, bounds.pieceLayer, from, it) }
      for (level in from..bounds.height) {
        if (level > from) row = pairs(row, level - 1)
        levels += row.fold(Buffer()) { buffer, hash -> buffer.write(hash) }.readByteString()
      }
      if (levels.last() != root) {
        if (!warned) {
          warned = true
          log.w {
            "Hash tree for a file of ${logHash(document.info.hash.hex)} did not match its root"
          }
        }
        lease.close()
        return null
      }
      val cache = Upper(levels, lease)
      upper[root] = cache
      cachedBytes += lease.bytes
      return cache
    } catch (error: Throwable) {
      lease.close()
      throw error
    }
  }

  private fun evictOldest() {
    val key = upper.keys.first()
    val cache = checkNotNull(upper.remove(key))
    cachedBytes -= cache.lease.bytes
    cache.lease.close()
  }

  /**
   * Block hashes of [piece], kept from an earlier proof or read back from storage; null when it
   * cannot be read now or [canRead] refuses it.
   */
  private suspend fun subtree(
    bounds: HashServeBounds,
    piece: Long,
    span: Int,
    read: suspend (Int) -> TorrentV2PieceStore.ReadOutcome,
  ): Subtree? {
    subtrees.remove(piece)?.let { kept ->
      if (kept.levels.size == span) {
        subtrees[piece] = kept
        return kept
      }
      kept.lease.close()
    }
    // Refused, it leaves the kept pieces alone.
    if (!canRead()) return null
    while (subtrees.size >= maxSubtrees) evictOldestSubtree()
    val hashBytes = (1 shl span) * 2 * 32 + 256
    var lease = state.reserve(hashBytes)
    while (lease == null && subtrees.isNotEmpty()) {
      evictOldestSubtree()
      lease = state.reserve(hashBytes)
    }
    if (lease == null) return null
    val leaves = 1 shl span
    try {
      val outcome = read(piece.toInt())
      if (outcome !is TorrentV2PieceStore.ReadOutcome.Read) {
        lease.close()
        return null
      }
      val rows = try {
        // Only a piece actually read is upload work the limits pay for.
        chargeRead(outcome.buffer.bytes.size.toLong())
        val bytes = outcome.buffer.bytes
        val firstBlock = (piece - bounds.firstPiece) shl span
        var row = (0 until leaves).map { offset ->
          val start = offset * TorrentMerkleRoot.BLOCK_BYTES
          if (firstBlock + offset >= bounds.blocks || start >= bytes.size) merkleZeroHash(0)
          else Sha256().update(bytes, start,
            minOf(TorrentMerkleRoot.BLOCK_BYTES, bytes.size - start)).digest()
        }
        buildList {
          for (level in 0 until span) {
            add(row.fold(Buffer()) { buffer, hash -> buffer.write(hash) }.readByteString())
            row = pairs(row, level)
          }
        }
      } finally { outcome.buffer.close() }
      return Subtree(piece, rows, lease).also { subtrees[piece] = it }
    } catch (error: Throwable) {
      lease.close()
      throw error
    }
  }

  private fun evictOldestSubtree() {
    val key = subtrees.keys.first()
    checkNotNull(subtrees.remove(key)).lease.close()
  }
}
