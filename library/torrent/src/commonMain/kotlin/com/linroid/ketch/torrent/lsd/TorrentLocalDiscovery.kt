package com.linroid.ketch.torrent.lsd

import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.torrent.TorrentMulticastLock
import com.linroid.ketch.torrent.crypto.torrentRandomBytes
import com.linroid.ketch.torrent.describeWithoutUrls
import com.linroid.ketch.torrent.logHash
import com.linroid.ketch.torrent.monotonicClock
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.net.TorrentDatagram
import com.linroid.ketch.torrent.net.numericAddress
import com.linroid.ketch.torrent.net.privateIpv4
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okio.ByteString
import okio.ByteString.Companion.decodeHex
import okio.ByteString.Companion.toByteString
import kotlin.concurrent.atomics.AtomicBoolean
import kotlin.concurrent.atomics.ExperimentalAtomicApi

/** The BEP 14 multicast groups, as a socket joins them, or null where it cannot. */
internal interface LsdTransport {
  /**
   * Sends [announce] to each group the transport joined (IPv4, IPv6 or both), on every interface
   * it joined that group on, with that group's `Host` header. Throws only when none went out.
   */
  suspend fun send(announce: LsdAnnounce)

  /** The next datagram any member sent the group, ours included. */
  suspend fun receive(): TorrentDatagram

  /**
   * Whether the interfaces it joined either group on are still this device's; once the network
   * changes, both groups are joined again on the current ones.
   */
  suspend fun isCurrent(): Boolean = true

  fun close()
}

/**
 * Joins the BEP 14 IPv4 group on every private IPv4 interface and the IPv6 group on every
 * interface with a link-local or unique local IPv6 address, sharing the port with other clients,
 * or null where this platform cannot (iOS, where multicast needs an entitlement).
 */
internal expect suspend fun openLsdMulticast(): LsdTransport?

/** Whether this platform can run local service discovery: [openLsdMulticast] can join the group. */
internal expect val lsdSupported: Boolean

/** BEP 14's IPv4 and IPv6 groups and their port. */
internal const val LSD_GROUP_IPV4 = "239.192.152.143"
internal const val LSD_GROUP_IPV6 = "ff15::efc0:988f"
internal const val LSD_PORT = 6771

/** The `Host` header of an announce sent to the IPv4 group. */
internal const val LSD_HOST_IPV4 = "$LSD_GROUP_IPV4:$LSD_PORT"

/** The `Host` header of an announce sent to the IPv6 group. */
internal const val LSD_HOST_IPV6 = "[$LSD_GROUP_IPV6]:$LSD_PORT"

/**
 * One BEP 14 announce: an HTTP-like `BT-SEARCH` request naming the TCP [port] peers reach the
 * sender on and the [hashes] (20 bytes each) of the torrents it wants peers for. [cookie] lets a
 * client recognize its own announces when the group sends them back.
 */
internal data class LsdAnnounce(
  val port: Int,
  val hashes: List<ByteString>,
  val cookie: String? = null,
) {
  init {
    require(port in 1..65535 && hashes.isNotEmpty() && hashes.all { it.size == 20 })
  }

  /** The datagram for the group whose `Host` header is [host]. */
  fun encode(host: String = LSD_HOST_IPV4): ByteArray = buildString {
    append("BT-SEARCH * HTTP/1.1\r\n")
    append("Host: $host\r\n")
    append("Port: $port\r\n")
    for (hash in hashes) append("Infohash: ${hash.hex()}\r\n")
    if (cookie != null) append("cookie: $cookie\r\n")
    append("\r\n\r\n")
  }.encodeToByteArray()

  companion object {
    /** Datagrams longer than this are refused, and ours stay shorter. */
    const val MAX_BYTES = 1400

    /** Infohashes one datagram carries at most: ours never carry more, and more are refused. */
    const val MAX_HASHES = 20

    private val HEX = Regex("[0-9a-fA-F]{40}")

    /**
     * Reads an announce, or null when [bytes] is not one: too long, another request line, no
     * valid `Port`, no 40-hex `Infohash`, or more than [MAX_HASHES] of them. Header names match
     * whatever their case, and lines may end with CRLF or LF.
     */
    fun parse(bytes: ByteArray): LsdAnnounce? {
      if (bytes.isEmpty() || bytes.size > MAX_BYTES) return null
      val lines = bytes.decodeToString().split('\n').map { it.removeSuffix("\r") }
      if (!lines.first().trim().equals("BT-SEARCH * HTTP/1.1", ignoreCase = true)) return null
      var port: Int? = null
      val hashes = mutableListOf<ByteString>()
      var cookie: String? = null
      for (line in lines.drop(1)) {
        if (line.isEmpty()) break
        val colon = line.indexOf(':')
        if (colon <= 0) return null
        val value = line.substring(colon + 1).trim()
        when (line.substring(0, colon).trim().lowercase()) {
          "port" -> {
            if (port != null) return null
            port = value.toIntOrNull()?.takeIf { it in 1..65535 } ?: return null
          }
          "infohash" -> {
            if (!HEX.matches(value)) return null
            hashes += value.lowercase().decodeHex()
            if (hashes.size > MAX_HASHES) return null
          }
          "cookie" -> cookie = value.take(64)
        }
      }
      if (port == null || hashes.isEmpty()) return null
      return LsdAnnounce(port, hashes.distinct(), cookie)
    }
  }
}

/** A torrent's hash in the group, and where its peers go; [deliver] must return at once. */
internal class LsdTopic(val hash: ByteString, val deliver: (PeerEndpoint) -> Unit) {
  init { require(hash.size == 20) }
}

/**
 * Whether an announce from [address] may come from our network: private or link-local IPv4,
 * link-local or unique local IPv6.
 */
internal fun lsdSenderAllowed(address: ByteArray): Boolean = when (address.size) {
  4 -> privateIpv4(address) ||
    (address[0].toInt() and 255) == 169 && (address[1].toInt() and 255) == 254
  // Link-local fe80::/10 and unique local fc00::/7.
  16 -> (address[0].toInt() and 255) == 0xfe && (address[1].toInt() and 0xc0) == 0x80 ||
    (address[0].toInt() and 0xfe) == 0xfc
  else -> false
}

/**
 * Whether an allowed sender can be dialed back: not an IPv6 link-local (fe80::/10) one, which is
 * reachable only through the interface it was heard on, a zone peer endpoints do not carry.
 */
internal fun lsdPeerDialable(address: ByteArray): Boolean =
  !(address.size == 16 && (address[0].toInt() and 255) == 0xfe &&
    (address[1].toInt() and 0xc0) == 0x80)

/**
 * Local Service Discovery (BEP 14): announces the torrents that [participate] while they run to
 * the LAN's multicast groups, and hands them the peers other clients announce for them, so
 * devices at home find each other without trackers or DHT. Only public torrents may participate:
 * the engine never registers private (BEP 27) or tracker-only ones.
 *
 * Each torrent is announced when it starts and every 5 minutes, never more than once a minute,
 * several to a datagram, to the IPv4 and the IPv6 group alike. A new peer, one not heard for one
 * of them in [NEW_PEER_MS], is answered with that announce as soon as that limit allows, so a
 * device that joins the LAN finds the others at once rather than at their next round, while peers
 * that hear each other every round never answer each other. The groups are joined only while
 * a torrent participates and [enabled]. Announces are accepted only from private or link-local
 * IPv4 and unique local IPv6 senders ([lsdSenderAllowed], [lsdPeerDialable]), at most
 * [ACCEPT_BURST] at once and [ACCEPT_PER_SECOND] a second after that, and each peer once a minute
 * per torrent. Ours come back from the groups too: our [cookie] tells them apart.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentLocalDiscovery(
  private val scope: CoroutineScope,
  /** Joins the group; null or a failure retries after [retryMs]. */
  private val open: suspend () -> LsdTransport?,
  /** The TCP port peers on the LAN reach us on; 0 while there is none, which skips announcing. */
  private val listenPort: () -> Int,
  enabled: Boolean,
  private val multicastLock: TorrentMulticastLock? = null,
  private val nowMs: () -> Long = monotonicClock(),
  private val cookie: String = torrentRandomBytes(8).toByteString().hex(),
  private val retryMs: Long = 60_000,
) {
  private val log = KetchLogger("TorrentLsd")
  private val enabled = AtomicBoolean(enabled)
  private val mutex = Mutex()
  private val wake = Channel<Unit>(Channel.CONFLATED)

  // Guarded by mutex.
  private val members = mutableListOf<List<LsdTopic>>()
  /** When each participating hash is announced next. */
  private val due = mutableMapOf<ByteString, Long>()
  /** When each hash was last announced; kept a minute after it stops participating. */
  private val announced = mutableMapOf<ByteString, Long>()
  private var runner: Job? = null

  // Owned by the receiving coroutine.
  private val delivered = LinkedHashMap<Pair<ByteString, PeerEndpoint>, Long>()
  private var tokens = ACCEPT_BURST.toDouble()
  private var refilledAt = Long.MIN_VALUE

  /** Whether announces go out and are heard; the setting can change while torrents run. */
  val isEnabled: Boolean get() = enabled.load()

  /**
   * Announces [topics] and delivers the peers announced for them until cancelled. A hybrid
   * passes one topic per swarm.
   */
  suspend fun participate(topics: List<LsdTopic>): Nothing {
    mutex.withLock {
      members += topics
      val now = nowMs()
      for (topic in topics) {
        if (topic.hash in due) continue
        val last = announced[topic.hash]
        due[topic.hash] = if (last == null) now else maxOf(now, last + MIN_INTERVAL_MS)
      }
      startRunner()
    }
    wake.trySend(Unit)
    try {
      awaitCancellation()
    } finally {
      withContext(NonCancellable) {
        mutex.withLock {
          members.removeAll { it === topics }
          val left = members.flatMapTo(mutableSetOf()) { member -> member.map { it.hash } }
          due.keys.retainAll(left)
          if (members.isEmpty()) stopRunner()
        }
      }
    }
  }

  /** Switches announcing and listening; torrents keep participating while it is off. */
  suspend fun setEnabled(value: Boolean) {
    enabled.store(value)
    mutex.withLock { if (value) startRunner() else stopRunner() }
    log.i { "Local service discovery: ${if (value) "on" else "off"}" }
  }

  /** Under [mutex]. */
  private fun startRunner() {
    if (!enabled.load() || members.isEmpty() || runner?.isActive == true) return
    runner = scope.launch { run() }
  }

  /** Under [mutex]. */
  private fun stopRunner() {
    runner?.cancel()
    runner = null
  }

  private suspend fun run() {
    while (currentCoroutineContext().isActive) {
      var lock: AutoCloseable? = null
      var transport: LsdTransport? = null
      try {
        lock = multicastLock?.acquire()
        transport = open()
        if (transport == null) {
          log.d { "Local service discovery is unavailable on this device" }
        } else {
          log.d { "Joined the local service discovery groups" }
          coroutineScope {
            val receiving = launch { receive(transport) }
            announce(transport)
            receiving.cancel()
          }
          log.d { "The network changed: joining the local service discovery groups again" }
          continue
        }
      } catch (e: CancellationException) {
        throw e
      } catch (e: Exception) {
        log.d { "Local service discovery stopped: ${e.describeWithoutUrls()}" }
      } finally {
        transport?.close()
        try { lock?.close() } catch (e: Exception) {
          log.d { "Releasing the multicast lock failed: ${e.describeWithoutUrls()}" }
        }
      }
      delay(retryMs)
    }
  }

  /**
   * Announces the hashes as they fall due until the network changes, then returns so [run] joins
   * the groups on the current interfaces. A send that fails on every interface throws.
   */
  private suspend fun announce(transport: LsdTransport) {
    var checkedAt = nowMs()
    while (true) {
      if (nowMs() - checkedAt >= NETWORK_CHECK_MS) {
        checkedAt = nowMs()
        if (!transport.isCurrent()) return
      }
      val port = listenPort()
      val now = nowMs()
      val hashes = mutableListOf<ByteString>()
      val next = mutex.withLock {
        if (port != 0) {
          for ((hash, at) in due) if (at <= now) hashes += hash
          for (hash in hashes) {
            due[hash] = now + ANNOUNCE_INTERVAL_MS
            announced[hash] = now
          }
          announced.entries.removeAll { (hash, at) -> hash !in due && now - at >= MIN_INTERVAL_MS }
        }
        due.values.minOrNull()
      }
      for (batch in hashes.chunked(LsdAnnounce.MAX_HASHES)) {
        try {
          transport.send(LsdAnnounce(port, batch, cookie))
        } catch (e: CancellationException) {
          throw e
        } catch (e: Exception) {
          // The group is joined again; what was not announced goes out then.
          mutex.withLock { for (hash in batch) if (hash in due) due[hash] = now }
          throw e
        }
      }
      if (hashes.isNotEmpty()) {
        log.d {
          "Announced ${hashes.size} torrent(s) on the LAN: " +
            hashes.joinToString { logHash(it.hex()) }
        }
      }
      val wait = if (port == 0 || next == null) 1_000L else (next - nowMs()).coerceIn(0, 60_000)
      withTimeoutOrNull(wait) { wake.receive() }
    }
  }

  private suspend fun receive(transport: LsdTransport): Nothing {
    while (true) accept(transport.receive())
  }

  /** Delivers the peer [datagram] announces to the torrents it names, unless refused. */
  internal suspend fun accept(datagram: TorrentDatagram) {
    val address = numericAddress(datagram.remote.host) ?: return
    if (!lsdSenderAllowed(address) || !lsdPeerDialable(address)) return
    val message = LsdAnnounce.parse(datagram.bytes) ?: return
    if (message.cookie == cookie) return
    if (!takeToken()) {
      log.v { "Dropped a local service discovery announce: too many" }
      return
    }
    val peer = PeerEndpoint(datagram.remote.host, message.port)
    val now = nowMs()
    val targets = mutex.withLock {
      message.hashes.flatMap { hash ->
        members.flatMap { member -> member.filter { it.hash == hash } }
      }
    }
    // Every participant of a hash hears the peer, as a task and a magnet lookup may share it.
    var answer = false
    for ((hash, topics) in targets.groupBy { it.hash }) {
      val key = hash to peer
      val last = delivered[key]
      if (last != null && now - last < MIN_INTERVAL_MS) continue
      // Peers heard every round are no newcomers: answering them would echo every minute.
      val newcomer = last == null || now - last >= NEW_PEER_MS
      delivered.remove(key)
      delivered[key] = now
      if (delivered.size > MAX_REMEMBERED) delivered.remove(delivered.keys.first())
      log.v { "LAN peer $peer for ${logHash(hash.hex())}" }
      for (topic in topics) topic.deliver(peer)
      if (newcomer && answerSoon(hash, now)) answer = true
    }
    if (answer) wake.trySend(Unit)
  }

  /**
   * Brings the next announce of [hash], which a new peer just announced, forward to the first
   * moment BEP 14 allows: a seeder never dials it, so it learns of us only from that announce.
   * False when [hash] no longer participates or is due no later anyway.
   */
  private suspend fun answerSoon(hash: ByteString, now: Long): Boolean = mutex.withLock {
    val next = due[hash] ?: return@withLock false
    val soonest = announced[hash]?.let { maxOf(now, it + MIN_INTERVAL_MS) } ?: now
    if (soonest >= next) return@withLock false
    due[hash] = soonest
    true
  }

  private fun takeToken(): Boolean {
    val now = nowMs()
    if (refilledAt != Long.MIN_VALUE) {
      tokens = minOf(ACCEPT_BURST.toDouble(),
        tokens + (now - refilledAt).coerceAtLeast(0) * ACCEPT_PER_SECOND / 1000.0)
    }
    refilledAt = now
    if (tokens < 1) return false
    tokens -= 1
    return true
  }

  companion object {
    /** BEP 14's period between announces of one torrent. */
    const val ANNOUNCE_INTERVAL_MS = 5 * 60_000L

    /** BEP 14's limit of one announce per torrent a minute; a peer is redelivered after it too. */
    const val MIN_INTERVAL_MS = 60_000L

    /**
     * How long a peer goes unheard for a torrent before an announce of it is answered: two
     * rounds, so peers that announce every round never are.
     */
    const val NEW_PEER_MS = 2 * ANNOUNCE_INTERVAL_MS

    /** How often a joined group checks whether the device's interfaces changed. */
    const val NETWORK_CHECK_MS = 60_000L

    /** Announces accepted at once, then [ACCEPT_PER_SECOND]. */
    const val ACCEPT_BURST = 32
    const val ACCEPT_PER_SECOND = 4

    /** Torrent and peer pairs remembered so each is delivered once a minute. */
    private const val MAX_REMEMBERED = 1024
  }
}
