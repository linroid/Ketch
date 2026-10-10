package com.linroid.ketch.torrent

import okio.Buffer

/**
 * One BEP 11 message: [added] and [dropped] peers, and the flags (`added.f`) its sender gave
 * each endpoint of [added] that came with them, paired before any entry was filtered.
 */
internal data class PexUpdate(
  val added: List<PeerEndpoint>,
  val dropped: List<PeerEndpoint>,
  val flags: Map<PeerEndpoint, Int> = emptyMap(),
)

/** BEP 11: the peer accepts incoming connections. */
internal const val PEX_FLAG_REACHABLE = 0x10

/**
 * The flags we advertise for a connected peer, the only place they are decided:
 * [PEX_FLAG_REACHABLE] only for peers we reached by dialing them, since only those are known to
 * accept connections. [link] and [extensions] carry what later transports and encryption add.
 */
@Suppress("UNUSED_PARAMETER")
internal fun pexFlags(
  reachedOutgoing: Boolean,
  link: TorrentConnection,
  extensions: PeerExtensions,
): Int = if (reachedOutgoing) PEX_FLAG_REACHABLE else 0

/**
 * Per-peer BEP 11 policy in both directions. Exchange data is a bounded, untrusted discovery hint.
 * What we tell a peer counts as told only once the message is handed on ([commit]), and stops
 * counting if it was then dropped unsent ([unsent]), so no change is ever lost.
 */
internal class PeerExchange(private val nowMs: () -> Long = monotonicClock()) {
  /** A message [message] built for one peer, telling it [added] and [dropped]. */
  class Outgoing internal constructor(
    val message: PeerMessage.Extended,
    internal val added: List<PeerEndpoint>,
    internal val dropped: List<PeerEndpoint>,
  )

  // When the messages of the current window arrived, oldest first.
  private val received = ArrayDeque<Long>()
  private var receivedAny = false
  private var sentAt: Long? = null
  private val advertised = linkedSetOf<PeerEndpoint>()
  // The last committed message, until a newer one is: the only one [unsent] can take back.
  private var lastSent: Outgoing? = null

  /**
   * Parses one message. Up to [MAX_MESSAGES_PER_WINDOW] may arrive in any minute, as clients
   * resend early; more is a violation. Oversized lists are cut to what one message may carry
   * rather than rejected, but peers both added and dropped are a violation.
   */
  fun receive(bytes: ByteArray): PexUpdate {
    val now = nowMs()
    while (received.isNotEmpty() && now - received.first() >= INTERVAL_MS) received.removeFirst()
    require(received.size < MAX_MESSAGES_PER_WINDOW) { "Peer exchange rate exceeded" }
    val root = Bencode.parse(bytes, 16 * 1024)
    val keys = listOf("added", "added6", "dropped", "dropped6")
    require(root.dictionary != null && keys.any { root[it] != null })
    // Flags pair with their entries by position, so they are matched before anything is dropped.
    fun entries(key: String, ipv6: Boolean): List<Pair<PeerEndpoint, Int?>> {
      val raw = root[key]?.bytes ?: return emptyList()
      val count = raw.size / (if (ipv6) 18 else 6)
      require(count <= MAX_PARSED_ENTRIES)
      val flags = root["$key.f"]?.let { node ->
        requireNotNull(node.bytes).also { require(it.size == count) }
      }
      return TorrentTracker.compactEntries(raw, ipv6).mapIndexedNotNull { index, endpoint ->
        endpoint?.let { it to flags?.let { flags -> flags[index].toInt() and 0xff } }
      }
    }
    val added = entries("added", false) + entries("added6", true)
    val dropped = (entries("dropped", false) + entries("dropped6", true)).map { it.first }
      .distinct()
    require(added.none { it.first in dropped })
    val kept = added.distinctBy { it.first.host }.take(if (receivedAny) 50 else 200)
    received.addLast(now)
    receivedAny = true
    return PexUpdate(kept.map { it.first }, dropped.take(50),
      kept.mapNotNull { (endpoint, flags) -> flags?.let { endpoint to it } }.toMap())
  }

  fun due(): Boolean = sentAt?.let { nowMs() - it >= INTERVAL_MS } ?: true

  /**
   * The next message for a peer whose `ut_pex` ID is [remoteId], or null when nothing changed or
   * the last one went out less than a minute ago. [contacts] are the listen endpoints of fully
   * handshaken live connections with the flags [pexFlags] gave them; the first 200 count.
   * Building it changes nothing: [commit] it once it is handed on, or the next call builds it
   * again.
   */
  fun message(remoteId: Int, contacts: Map<PeerEndpoint, Int>): Outgoing? {
    if (remoteId == 0 || sentAt?.let { nowMs() - it < INTERVAL_MS } == true) return null
    val live = contacts.keys.filter { numericAddress(it.host) != null }.take(MAX_CONTACTS).toSet()
    val dropped = advertised.filter { it !in live }.take(50)
    val added = live.filter { it !in advertised }.take(50)
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
          if (name == "added") {
            values["$key.f"] = ByteArray(family.size) { (contacts[family[it]] ?: 0).toByte() }
          }
        }
      }
    }
    return Outgoing(PeerMessage.Extended(remoteId, Bencode.encode(values)), added, dropped)
  }

  /** [outgoing] was handed on: the peer knows what it says, and the next waits a minute. */
  fun commit(outgoing: Outgoing) {
    advertised.removeAll(outgoing.dropped.toSet())
    advertised.addAll(outgoing.added)
    sentAt = nowMs()
    lastSent = outgoing
  }

  /**
   * [message], committed last, was dropped before it reached the peer: what it said is untold
   * again, and the next message may go out at once. False for any other message.
   */
  fun unsent(message: PeerMessage.Extended): Boolean {
    val last = lastSent?.takeIf { it.message === message } ?: return false
    advertised.removeAll(last.added.toSet())
    advertised.addAll(last.dropped)
    sentAt = null
    lastSent = null
    return true
  }

  private companion object {
    const val INTERVAL_MS = 60_000L
    /** libtorrent may send a message early after a reconnect; allow some jitter. */
    const val MAX_MESSAGES_PER_WINDOW = 3
    const val MAX_PARSED_ENTRIES = 200
    const val MAX_CONTACTS = 200
  }
}

/** Hosts one peer may introduce by peer exchange over its connection; see [PexIntroductions]. */
internal const val MAX_PEX_INTRODUCTIONS = 50

/**
 * The hosts one connection introduced by peer exchange, over its whole life: one endpoint per
 * host and at most [limit] hosts, for v1 and v2 peers alike. A host the peer later drops stays
 * counted, as what it introduced may already wait to be dialed, so dropping makes no room for
 * more. Owned by whoever reads that connection.
 */
internal class PexIntroductions(private val limit: Int = MAX_PEX_INTRODUCTIONS) {
  private val hosts = mutableSetOf<String>()

  init { require(limit > 0) }

  /** True, and counted, when [endpoint]'s host is new from this peer and within its limit. */
  fun admit(endpoint: PeerEndpoint): Boolean {
    if (endpoint.host in hosts || hosts.size >= limit) return false
    hosts += endpoint.host
    return true
  }
}

/** Where a candidate endpoint came from. [INCOMING] is the listen port of a peer that dialed us. */
internal enum class PeerOrigin { TRACKER, DHT, PEX, EXPLICIT, INCOMING }

/**
 * Flags peers advertised per endpoint (BEP 11 `added.f`), kept beside the endpoints rather than
 * in them. Single-owner LRU: recording an endpoint again makes it the newest, and the oldest
 * leave once more than [capacity] are known.
 */
internal class PeerFlagBook(private val capacity: Int = 4096) {
  private val flags = LinkedHashMap<PeerEndpoint, Int>()

  init { require(capacity > 0) }

  val size: Int get() = flags.size

  fun record(flags: Map<PeerEndpoint, Int>) {
    for ((endpoint, value) in flags) {
      this.flags.remove(endpoint)
      this.flags[endpoint] = value and 0xff
      if (this.flags.size > capacity) this.flags.remove(this.flags.keys.first())
    }
  }

  /** Zero when nobody advertised flags for [endpoint]. */
  operator fun get(endpoint: PeerEndpoint): Int = flags[endpoint] ?: 0
}

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
