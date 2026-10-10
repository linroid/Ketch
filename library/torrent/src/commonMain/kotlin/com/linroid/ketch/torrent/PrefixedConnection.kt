package com.linroid.ketch.torrent

/**
 * Serves [prefix] again, for any split of [readExactly] calls, then reads [delegate]. One reader.
 * Every other member is delegated, so properties later added to [TorrentConnection] forward.
 * Returned arrays are always new, never the prefix itself. A read that fails after consuming
 * part of the prefix leaves the stream unusable; the caller closes it.
 */
internal class PrefixedConnection(
  private val delegate: TorrentConnection,
  prefix: ByteArray,
) : TorrentConnection by delegate {
  private var prefix: ByteArray? = prefix.copyOf().takeIf { it.isNotEmpty() }
  private var offset = 0

  override suspend fun readExactly(size: Int): ByteArray {
    require(size >= 0) { "Negative read size" }
    val buffered = prefix ?: return delegate.readExactly(size)
    val remaining = buffered.size - offset
    if (size < remaining) {
      return buffered.copyOfRange(offset, offset + size).also { offset += size }
    }
    prefix = null
    val head = buffered.copyOfRange(offset, buffered.size)
    if (size == remaining) return head
    return head + delegate.readExactly(size - remaining)
  }
}
