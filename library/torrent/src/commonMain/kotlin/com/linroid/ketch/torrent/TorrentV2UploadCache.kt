package com.linroid.ketch.torrent

/**
 * Verified pieces a session is uploading, least recently used first. Owned by the session loop;
 * each entry keeps the reservation its read made. At most [capacityBytes] are kept, but always
 * one piece, and entries idle for [idleMs] are dropped. Callers copy the slices they send, so a
 * pending upload never pins an entry.
 */
internal class TorrentV2UploadCache(
  private val capacityBytes: Long,
  private val nowMs: () -> Long,
  private val idleMs: Long = 5_000,
) {
  private class Entry(val buffer: TorrentV2PieceStore.ReadBuffer, var usedAt: Long)

  init { require(capacityBytes > 0 && idleMs > 0) }

  // Access order: the first entry is the least recently used.
  private val entries = LinkedHashMap<Int, Entry>()
  private var closed = false

  val size: Int get() = entries.size
  val bytes: Long get() = entries.values.sumOf { it.buffer.bytes.size.toLong() }

  /** The verified bytes of piece [index], or null when they are not cached. */
  operator fun get(index: Int): ByteArray? {
    val entry = entries.remove(index) ?: return null
    entry.usedAt = nowMs()
    entries[index] = entry
    return entry.buffer.bytes
  }

  operator fun contains(index: Int): Boolean = index in entries

  /** Takes ownership of [buffer], dropping the least recently used pieces beyond the capacity. */
  fun put(index: Int, buffer: TorrentV2PieceStore.ReadBuffer) {
    if (closed || index in entries) {
      buffer.close()
      return
    }
    entries[index] = Entry(buffer, nowMs())
    while (entries.size > 1 && bytes > capacityBytes) evict(entries.keys.first())
  }

  /**
   * Drops the least recently used entries, except those in [keep], until at most [bytes] are
   * kept, possibly none.
   */
  fun shrink(bytes: Long, keep: Set<Int> = emptySet()) {
    val candidates = entries.keys.filter { it !in keep }.iterator()
    while (this.bytes > bytes && candidates.hasNext()) evict(candidates.next())
  }

  /** Bytes [shrink] could drop while keeping the pieces in [keep]. */
  fun evictableBytes(keep: Set<Int>): Long =
    entries.filterKeys { it !in keep }.values.sumOf { it.buffer.bytes.size.toLong() }

  /** Drops entries idle for [idleMs]. */
  fun expire() {
    val now = nowMs()
    entries.filterValues { now - it.usedAt >= idleMs }.keys.forEach(::evict)
  }

  /** Milliseconds until the next entry expires, or null while empty. */
  fun nextExpiryMs(): Long? {
    val now = nowMs()
    return entries.values.minOfOrNull { (it.usedAt + idleMs - now).coerceAtLeast(0) }
  }

  /** Gives every reservation back, so downloads blocked on the transfer budget can proceed. */
  fun trim() = entries.keys.toList().forEach(::evict)

  fun close() {
    closed = true
    trim()
  }

  private fun evict(index: Int) {
    entries.remove(index)?.buffer?.close()
  }

  companion object {
    /**
     * Half of [uploads], the budget upload reads are charged to (a quarter of the transfer
     * budget in an engine), between one and four pieces.
     */
    fun capacity(pieceLength: Long, uploads: TorrentBufferBudget): Long =
      maxOf(pieceLength, minOf(4 * pieceLength, uploads.capacity / 2L))
  }
}
