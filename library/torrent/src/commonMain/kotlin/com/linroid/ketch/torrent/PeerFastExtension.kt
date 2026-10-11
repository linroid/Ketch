package com.linroid.ketch.torrent

/** Allowed-fast pieces we offer a peer (BEP 6 suggests 10). */
internal const val ALLOWED_FAST_COUNT = 10

/**
 * BEP 6's canonical allowed-fast set: the pieces a peer at [ipv4] may download from us while
 * we choke it. Peers in one /24 share the set, so many addresses gain no more pieces. SHA-1 of
 * the masked address and [infoHash], then of each digest in turn, yields five 32-bit indices a
 * round; repeats are skipped. At most [pieceCount] pieces, in the order they were drawn.
 */
internal fun allowedFastSet(
  ipv4: ByteArray,
  infoHash: ByteArray,
  pieceCount: Int,
  count: Int = ALLOWED_FAST_COUNT,
): List<Int> {
  require(ipv4.size == 4 && infoHash.size == 20 && pieceCount >= 0 && count >= 0)
  val wanted = minOf(count, pieceCount)
  val allowed = LinkedHashSet<Int>()
  var x = ipv4.copyOf().also { it[3] = 0 } + infoHash
  while (allowed.size < wanted) {
    x = sha1Digest(x)
    for (i in 0 until 5) {
      if (allowed.size >= wanted) break
      var value = 0L
      for (offset in 4 * i until 4 * i + 4) value = (value shl 8) or (x[offset].toLong() and 255L)
      allowed += (value % pieceCount).toInt()
    }
  }
  return allowed.toList()
}

/**
 * The IPv4 address of a numeric [host], also written as an IPv4-mapped IPv6 address; null for
 * IPv6 and names. BEP 6 only defines the allowed-fast set for IPv4 peers.
 */
internal fun allowedFastAddress(host: String): ByteArray? {
  val address = numericAddress(host) ?: return null
  if (address.size == 4) return address
  val mapped = address.size == 16 && (0 until 10).all { address[it] == 0.toByte() } &&
    address[10] == (-1).toByte() && address[11] == (-1).toByte()
  return if (mapped) address.copyOfRange(12, 16) else null
}

/** A message that only the Fast extension (BEP 6) defines. */
internal fun PeerMessage.isFastExtension(): Boolean = this is PeerMessage.Suggest ||
  this == PeerMessage.HaveAll || this == PeerMessage.HaveNone || this is PeerMessage.Reject ||
  this is PeerMessage.AllowedFast
