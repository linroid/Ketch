package com.linroid.ketch.torrent

import okio.Buffer

internal data class PexUpdate(val added: List<PeerEndpoint>, val dropped: List<PeerEndpoint>)

/** Per-peer BEP 11 receive policy. Exchange data is a bounded, untrusted discovery hint. */
internal class PeerExchange(private val nowMs: () -> Long = monotonicClock()) {
  private var receivedAt: Long? = null
  private var sentAt: Long? = null
  private val advertised = linkedSetOf<PeerEndpoint>()

  fun receive(bytes: ByteArray): PexUpdate {
    val now = nowMs()
    require(receivedAt == null || now - receivedAt!! >= 60_000) { "Peer exchange rate exceeded" }
    val root = Bencode.parse(bytes, 16 * 1024)
    val keys = listOf("added", "added6", "dropped", "dropped6")
    require(root.dictionary != null && keys.any { root[it] != null })
    fun peers(key: String, ipv6: Boolean): List<PeerEndpoint> {
      val raw = root[key]?.bytes ?: return emptyList()
      require(raw.size / (if (ipv6) 18 else 6) <= 200)
      val peers = TorrentTracker.compactPeers(raw, ipv6)
      root["$key.f"]?.let { require(it.bytes?.size == raw.size / (if (ipv6) 18 else 6)) }
      return peers
    }
    val added = peers("added", false) + peers("added6", true)
    val dropped = peers("dropped", false) + peers("dropped6", true)
    require(added.size <= (if (receivedAt == null) 200 else 50) && dropped.size <= 50)
    require(added.none { it in dropped })
    receivedAt = now
    return PexUpdate(added.distinctBy { it.host }, dropped.distinct())
  }

  fun due(): Boolean = sentAt?.let { nowMs() - it >= 60_000 } ?: true

  /** Only fully handshaken live connections belong in this snapshot. */
  fun message(remoteId: Int, connected: Set<PeerEndpoint>): PeerMessage.Extended? {
    if (remoteId == 0 || sentAt?.let { nowMs() - it < 60_000 } == true) return null
    val dropped = advertised.filter { it !in connected }.take(50)
    val added = connected.filter { it !in advertised && numericAddress(it.host) != null }.take(50)
    if (added.isEmpty() && dropped.isEmpty()) return null
    val values = mutableMapOf<String, Any>()
    for ((name, peers) in listOf("added" to added, "dropped" to dropped)) {
      for (ipv6 in listOf(false, true)) {
        val family = peers.filter { (':' in it.host) == ipv6 }
        if (family.isNotEmpty()) {
          val key = name + if (ipv6) "6" else ""
          val compact = Buffer()
          family.forEach { compact.write(DhtCodec.compactEndpoint(it)) }
          values[key] = compact.readByteArray()
          if (name == "added") values["$key.f"] = ByteArray(family.size) { 0x10 }
        }
      }
    }
    advertised.removeAll(dropped.toSet())
    advertised.addAll(added)
    sentAt = nowMs()
    return PeerMessage.Extended(remoteId, Bencode.encode(values))
  }
}

internal enum class PeerOrigin { TRACKER, DHT, PEX, EXPLICIT }

/** Tracks provenance and removes only the departing source's claim on a candidate. */
internal class TorrentPeerDirectory(private val privateTorrent: Boolean) {
  private data class Source(val origin: PeerOrigin, val identity: String)
  private val peers = linkedMapOf<PeerEndpoint, MutableSet<Source>>()
  private var tracker: String? = null

  fun update(
    origin: PeerOrigin,
    identity: String,
    added: List<PeerEndpoint>,
    dropped: List<PeerEndpoint> = emptyList(),
  ): Boolean {
    if (privateTorrent && origin != PeerOrigin.TRACKER) return false
    var switched = false
    if (privateTorrent && tracker != identity) {
      switched = tracker != null
      peers.clear()
      tracker = identity
    }
    val source = Source(origin, identity)
    for (endpoint in dropped) {
      peers[endpoint]?.let { it.remove(source); if (it.isEmpty()) peers.remove(endpoint) }
    }
    val limit = if (origin == PeerOrigin.PEX) 50 else 256
    val existing = peers.count { source in it.value }
    var remaining = (limit - existing).coerceAtLeast(0)
    for (endpoint in added) {
      if (remaining == 0 || peers.size >= 4096) break
      if (endpoint.port == 0) continue
      if (origin == PeerOrigin.PEX && peers.keys.any { it.host == endpoint.host }) continue
      val sources = peers.getOrPut(endpoint) { mutableSetOf() }
      if (sources.size < 8 && sources.add(source)) remaining--
    }
    return switched
  }

  fun candidates(): List<PeerEndpoint> = peers.keys.toList()
}
