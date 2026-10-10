package com.linroid.ketch.torrent

import okio.ByteString

/** The swarm a discovered endpoint belongs to: the v1 hash's or the v2 hash's. */
internal enum class PeerTopic { V1, V2 }

/** One endpoint discovery found, with the [flags] a peer advertised for it (PEX only). */
internal data class TorrentV2Discovered(
  val endpoint: PeerEndpoint,
  val topic: PeerTopic,
  val origin: PeerOrigin,
  val flags: Int = 0,
)

/** What a dial worker needs to reach one candidate; workers never read the candidate book. */
internal data class TorrentV2DialTarget(
  val endpoint: PeerEndpoint,
  val mode: PeerIdentityHandshake.Mode,
  /** Offer the hybrid upgrade bit on a v1 handshake. */
  val upgrade: Boolean,
  /** The session generation the target was chosen in; a reset makes it stale. */
  val generation: Long,
  /** Flags a peer advertised for this endpoint, for later transports to choose by. */
  val pexFlags: Int = 0,
)

/**
 * The endpoints a v2 session may dial, owned by its loop. Endpoints are deduplicated, retried
 * with backoff a bounded number of times while the session is incomplete, and banned after
 * protocol violations; a reset forgets everything but the bans. A peer ID is only what a peer
 * claims, so it is banned only together with the host it came from. Flags peers advertised
 * leave the book only inside dial targets. Restricted sessions (private metainfo or tracker-only
 * privacy) take only tracker peers.
 */
internal class TorrentV2Candidates(
  private val hybrid: Boolean,
  private val restricted: Boolean,
  private val nowMs: () -> Long,
) {
  /** [NEW] and [EXHAUSTED] entries are known but not counted as work. */
  private enum class Status { NEW, QUEUED, WAITING, DIALING, CONNECTED, EXHAUSTED }

  private class Entry(val endpoint: PeerEndpoint) {
    var status = Status.NEW
    /** Some source found it in the v2 swarm, so a hybrid dials it in v2 mode. */
    var v2 = false
    /** It refused a hybrid's v2 handshake, so it is dialed in v1 mode from then on. */
    var v1Only = false
    var attempts = 0
    var windowStart = 0L
    var retryAt = 0L
  }

  private val entries = LinkedHashMap<PeerEndpoint, Entry>()
  private val queue = ArrayDeque<PeerEndpoint>()
  private val waiting = mutableListOf<PeerEndpoint>()
  private val bannedEndpoints = LinkedHashMap<PeerEndpoint, Unit>()
  private val bannedPeers = LinkedHashMap<BannedPeer, Unit>()
  private val flags = PeerFlagBook(MAX_CANDIDATES)
  private var resetGeneration = 0L
  private var queued = 0
  private var dialing = 0

  /** Endpoints waiting to be dialed now or after a backoff; for logs. */
  val pendingCount: Int get() = queued + waiting.size

  /** Records [endpoint]; offers already known, connected, banned or refused are ignored. */
  fun offer(endpoint: PeerEndpoint, topic: PeerTopic, origin: PeerOrigin, flags: Int = 0) {
    // Flags describe the endpoint whatever happens to the offer itself.
    if (origin == PeerOrigin.PEX) this.flags.record(mapOf(endpoint to flags))
    if (restricted && origin != PeerOrigin.TRACKER) return
    if (endpoint.port == 0 || endpoint in bannedEndpoints) return
    val existing = entries[endpoint]
    if (existing != null) {
      if (topic == PeerTopic.V2) existing.v2 = true
      // Found again once its attempt window has passed: it may be tried again.
      if (existing.status == Status.EXHAUSTED &&
        nowMs() - existing.windowStart >= ATTEMPT_WINDOW_MS) {
        existing.attempts = 0
        enqueue(existing)
      }
      return
    }
    if (entries.size >= MAX_CANDIDATES) prune()
    if (entries.size >= MAX_CANDIDATES) return
    val entry = Entry(endpoint)
    entry.v2 = topic == PeerTopic.V2
    entries[endpoint] = entry
    enqueue(entry)
  }

  /**
   * The generation this book belongs to, from its last [reset]. Targets carry it, so a target
   * chosen before the loop applied a reset is stale even if the session already counts the next.
   */
  val generation: Long get() = resetGeneration

  /** The next endpoint to dial, stamped with [generation], or null when none is due. */
  fun next(): TorrentV2DialTarget? {
    promoteDue()
    while (queue.isNotEmpty()) {
      val entry = entries[queue.removeFirst()] ?: continue
      if (entry.status != Status.QUEUED) continue
      val now = nowMs()
      if (entry.attempts == 0 || now - entry.windowStart >= ATTEMPT_WINDOW_MS) {
        entry.attempts = 0
        entry.windowStart = now
      }
      entry.attempts++
      move(entry, Status.DIALING)
      val v1 = hybrid && (!entry.v2 || entry.v1Only)
      val mode = if (v1) PeerIdentityHandshake.Mode.V1 else PeerIdentityHandshake.Mode.V2
      return TorrentV2DialTarget(entry.endpoint, mode, upgrade = v1, generation = generation,
        pexFlags = flags[entry.endpoint])
    }
    return null
  }

  /** Returns a [target] the dial queue refused, so it is dialed first next time. */
  fun pushBack(target: TorrentV2DialTarget) {
    if (target.generation < resetGeneration) return
    val entry = entries[target.endpoint] ?: return
    if (entry.status != Status.DIALING) return
    entry.attempts--
    move(entry, Status.QUEUED)
    queue.addFirst(entry.endpoint)
  }

  /** A connection to [endpoint] is up, dialed or learned as an incoming peer's listen port. */
  fun connected(endpoint: PeerEndpoint) {
    val entry = entries.getOrPut(endpoint) { Entry(endpoint) }
    move(entry, Status.CONNECTED)
  }

  /**
   * A dial failed: a protocol violation bans the endpoint, anything else backs off. A hybrid's
   * v2 dial that the peer answered for another swarm, or hung up on, is dialed again at once in
   * v1 mode with the upgrade offered: such a peer may only know the v1 swarm.
   */
  fun failed(target: TorrentV2DialTarget, cause: Throwable, complete: Boolean = false) {
    if (target.generation < resetGeneration) return
    val entry = entries[target.endpoint] ?: return
    if (entry.status != Status.DIALING) return
    if (hybrid && target.mode == PeerIdentityHandshake.Mode.V2 && !entry.v1Only &&
      (cause is PeerSwarmChangedException || cause is PeerHandshakeUnansweredException)) {
      entry.v1Only = true
      // The wrong mode is not the endpoint's failure, so the attempt is not counted.
      entry.attempts--
      enqueue(entry)
    } else if (cause is IllegalArgumentException) ban(target.endpoint)
    else retry(entry, complete)
  }

  /**
   * The connection to a dialed or learned [endpoint] closed. A [complete] session dials it again
   * only once some source finds it after its attempt window.
   */
  fun closed(endpoint: PeerEndpoint, violation: Boolean, complete: Boolean = false) {
    if (violation) {
      ban(endpoint)
      return
    }
    val entry = entries[endpoint] ?: return
    if (entry.status == Status.CONNECTED || entry.status == Status.DIALING) retry(entry, complete)
  }

  /**
   * An incoming peer from [source] closed. Its source port is ephemeral and never dialed; its
   * [listen] port, when the peer told us, becomes a candidate unless the session is restricted.
   * A [complete] session does not dial it back: it is kept as tried until its window passes.
   */
  fun incomingClosed(
    source: PeerEndpoint,
    listen: PeerEndpoint?,
    topic: PeerTopic,
    complete: Boolean = false,
  ) {
    if (listen == null || listen == source) return
    if (!complete) {
      offer(listen, topic, PeerOrigin.INCOMING)
      return
    }
    if (listen.port == 0 || listen in bannedEndpoints) return
    val entry = entries[listen] ?: run {
      if (entries.size >= MAX_CANDIDATES) prune()
      if (entries.size >= MAX_CANDIDATES) return
      Entry(listen).also { entries[listen] = it }
    }
    if (entry.status == Status.DIALING || entry.status == Status.CONNECTED) return
    entry.windowStart = nowMs()
    move(entry, Status.EXHAUSTED)
  }

  /**
   * The session completed: endpoints still backing off from failures while it downloaded are
   * not retried either. Each is tried again only once some source finds it after its window.
   */
  fun completed() {
    val now = nowMs()
    for (endpoint in waiting.toList()) {
      val entry = entries.getValue(endpoint)
      entry.windowStart = now
      move(entry, Status.EXHAUSTED)
    }
  }

  /** Never dials [endpoint] again, until it leaves the bounded list. */
  fun ban(endpoint: PeerEndpoint) {
    remember(bannedEndpoints, endpoint)
    entries.remove(endpoint)?.let { move(it, Status.EXHAUSTED) }
  }

  /**
   * Refuses connections from [host] that claim [peerId], until the pair leaves the bounded list.
   * Another host claiming the same ID is unaffected.
   */
  fun ban(host: String, peerId: ByteString) = remember(bannedPeers, BannedPeer(host, peerId))

  fun isBanned(host: String, peerId: ByteString): Boolean = BannedPeer(host, peerId) in bannedPeers

  /** Starts [generation]: forgets every candidate and attempt, keeps bans and flags. */
  fun reset(generation: Long) {
    require(generation >= resetGeneration)
    resetGeneration = generation
    entries.clear()
    queue.clear()
    waiting.clear()
    queued = 0
    dialing = 0
  }

  /** Something is queued, backing off or being dialed. */
  fun hasWork(): Boolean = queued > 0 || waiting.isNotEmpty() || dialing > 0

  /** Milliseconds until the next backed-off endpoint is due, or null when none waits. */
  fun nextDueMs(): Long? {
    if (waiting.isEmpty()) return null
    val now = nowMs()
    return waiting.minOf { (entries.getValue(it).retryAt - now).coerceAtLeast(0) }
  }

  private fun retry(entry: Entry, complete: Boolean) {
    // A seed retries nobody: the peer may dial us, or a source finds it again later.
    if (complete) entry.windowStart = nowMs()
    if (complete || entry.attempts > RETRY_DELAYS_MS.size) {
      // Kept until its window passes, so the same endpoint is not dialed again meanwhile.
      move(entry, Status.EXHAUSTED)
      return
    }
    entry.retryAt = nowMs() + RETRY_DELAYS_MS[entry.attempts - 1]
    move(entry, Status.WAITING)
  }

  private fun promoteDue() {
    if (waiting.isEmpty()) return
    val now = nowMs()
    val due = waiting.filter { entries.getValue(it).retryAt <= now }
    for (endpoint in due) enqueue(entries.getValue(endpoint))
  }

  private fun enqueue(entry: Entry) {
    move(entry, Status.QUEUED)
    queue.addLast(entry.endpoint)
  }

  private fun move(entry: Entry, status: Status) {
    when (entry.status) {
      Status.QUEUED -> queued--
      Status.WAITING -> waiting.remove(entry.endpoint)
      Status.DIALING -> dialing--
      else -> Unit
    }
    entry.status = status
    when (status) {
      Status.QUEUED -> queued++
      Status.WAITING -> waiting += entry.endpoint
      Status.DIALING -> dialing++
      else -> Unit
    }
  }

  /** Forgets exhausted endpoints whose window has passed, to make room for new ones. */
  private fun prune() {
    val now = nowMs()
    entries.values.filter {
      it.status == Status.EXHAUSTED && now - it.windowStart >= ATTEMPT_WINDOW_MS
    }.forEach { entries.remove(it.endpoint) }
  }

  private data class BannedPeer(val host: String, val peerId: ByteString)

  private fun <K> remember(bans: LinkedHashMap<K, Unit>, key: K) {
    bans.remove(key)
    bans[key] = Unit
    if (bans.size > MAX_BANS) bans.remove(bans.keys.first())
  }

  companion object {
    const val MAX_CANDIDATES = 4096
    const val MAX_BANS = 1024
    /** Dialed again after 5, 15 and 30 s, like metadata peers; then not until the window ends. */
    val RETRY_DELAYS_MS = listOf(5_000L, 15_000L, 30_000L)
    const val ATTEMPT_WINDOW_MS = 300_000L

    /** Session-state charge for a full book: entries, both ban lists and the flag book. */
    const val STATE_BYTES =
      MAX_CANDIDATES * 96 + MAX_BANS * (64 + 160) + MAX_CANDIDATES * 24 + 4096
  }
}
