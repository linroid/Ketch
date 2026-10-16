package com.linroid.ketch.torrent.v1

import com.linroid.ketch.torrent.monotonicClock
import com.linroid.ketch.torrent.net.PeerEndpoint
import com.linroid.ketch.torrent.peer.HolepunchError
import com.linroid.ketch.torrent.peer.HolepunchMessage
import com.linroid.ketch.torrent.peer.HolepunchType
import kotlinx.coroutines.channels.SendChannel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * A swarm's side of hole punching (BEP 55): which connected peers take part, and which peer
 * introduced each endpoint by peer exchange.
 *
 * As a relay it answers a peer's rendezvous by sending both it and the peer it names a CONNECT
 * with the other's endpoint, so each dials the other over uTP at once and their NATs let the
 * other's packets in. When we cannot dial a peer ourselves, [rendezvous] asks the peer that
 * introduced it to do the same for us. Each worker hands in the messages for its peer through
 * its own outbox and sends them on its next pass.
 */
internal class SwarmHolepunch(private val nowMs: () -> Long = monotonicClock()) {
  private class Member(
    val outbox: SendChannel<HolepunchMessage>,
    val remote: PeerEndpoint,
    /** The peer reached us, or we it, over uTP: [remote] is then where its NAT maps uTP. */
    val utp: Boolean,
  ) {
    var listen: PeerEndpoint? = null
    var supports = false
  }

  private val mutex = Mutex()
  // Guarded by mutex.
  private val members = mutableMapOf<Int, Member>()
  private val introducers = LinkedHashMap<PeerEndpoint, Int>()
  private val asked = LinkedHashMap<PeerEndpoint, Long>()

  /** Counters since the last [summary]. */
  private var relayed = 0
  private var requested = 0
  private var connects = 0
  /** Errors relays answered our rendezvous with, by BEP 55 code. */
  private val errors = mutableMapOf<Int, Int>()

  suspend fun join(
    id: Int,
    remote: PeerEndpoint,
    utp: Boolean,
    outbox: SendChannel<HolepunchMessage>,
  ) {
    mutex.withLock { members[id] = Member(outbox, remote, utp) }
  }

  /** What the peer's extension handshake said: where it listens and whether it takes part. */
  suspend fun update(id: Int, listen: PeerEndpoint?, supports: Boolean) = mutex.withLock {
    members[id]?.let {
      it.listen = listen
      it.supports = supports
    }
  }

  suspend fun leave(id: Int) {
    mutex.withLock { members.remove(id) }
  }

  /** The peer [by] told us about [peers]; the last introducer of each is asked to punch. */
  suspend fun introduced(by: Int, peers: Collection<PeerEndpoint>) = mutex.withLock {
    for (peer in peers) {
      introducers.remove(peer)
      introducers[peer] = by
    }
    while (introducers.size > MAX_TRACKED) introducers.remove(introducers.keys.first())
  }

  /**
   * Answers peer [from]'s rendezvous for [target]: a CONNECT to each with the other's endpoint
   * when we are connected to [target] and it takes part, an error to [from] otherwise. A peer
   * that did not offer the extension is ignored, as BEP 55 requires.
   */
  suspend fun relay(from: Int, target: PeerEndpoint) = mutex.withLock {
    val sender = members[from]?.takeIf { it.supports } ?: return@withLock
    fun fail(code: Int) {
      sender.outbox.trySend(HolepunchMessage(HolepunchType.ERROR, target, code))
    }
    if (target == sender.remote || target == sender.listen) {
      return@withLock fail(HolepunchError.NO_SELF)
    }
    val other = members.entries.firstOrNull { (id, member) ->
      id != from && (member.listen == target || member.remote == target)
    }?.value ?: return@withLock fail(HolepunchError.NOT_CONNECTED)
    if (!other.supports) return@withLock fail(HolepunchError.NO_SUPPORT)
    other.outbox.trySend(HolepunchMessage(HolepunchType.CONNECT, sender.punchEndpoint()))
    sender.outbox.trySend(HolepunchMessage(HolepunchType.CONNECT, other.punchEndpoint()))
    relayed++
  }

  /**
   * Asks the peer that introduced [target] to introduce us, at most once per [RETRY_MS] for each
   * target; false when no connected peer that takes part introduced it.
   */
  suspend fun rendezvous(target: PeerEndpoint): Boolean = mutex.withLock {
    val now = nowMs()
    if (asked[target]?.let { now - it < RETRY_MS } == true) return@withLock false
    val introducer = introducers[target]?.let(members::get)?.takeIf { it.supports }
      ?: return@withLock false
    if (!introducer.outbox.trySend(HolepunchMessage(HolepunchType.RENDEZVOUS, target)).isSuccess) {
      return@withLock false
    }
    asked.remove(target)
    asked[target] = now
    while (asked.size > MAX_TRACKED) asked.remove(asked.keys.first())
    requested++
    true
  }

  /** A relay told us to dial a peer. */
  suspend fun connected() {
    mutex.withLock { connects++ }
  }

  /** A relay could not introduce us, for BEP 55 error [code]. */
  suspend fun failed(code: Int) = mutex.withLock {
    errors[code] = (errors[code] ?: 0) + 1
  }

  /**
   * "holepunch peers=3 relayed=1 asked=2 connects=1 errors=notConnected:1", counting since the
   * last summary.
   */
  suspend fun summary(): String = mutex.withLock {
    val peers = members.values.count { it.supports }
    val failures = if (errors.isEmpty()) "" else
      " errors=" + errors.entries.sortedBy { it.key }
        .joinToString(",") { (code, count) -> "${errorName(code)}:$count" }
    ("holepunch peers=$peers relayed=$relayed asked=$requested connects=$connects$failures").also {
      relayed = 0
      requested = 0
      connects = 0
      errors.clear()
    }
  }

  private fun errorName(code: Int): String = when (code) {
    HolepunchError.NO_SUCH_PEER -> "noSuchPeer"
    HolepunchError.NOT_CONNECTED -> "notConnected"
    HolepunchError.NO_SUPPORT -> "noSupport"
    HolepunchError.NO_SELF -> "noSelf"
    else -> "code$code"
  }

  /**
   * Where the other peer should dial this one over uTP: the endpoint it reached us from when
   * that was uTP, which its NAT maps for UDP, else where it listens, which NATs that keep ports
   * map the same way.
   */
  private fun Member.punchEndpoint(): PeerEndpoint = if (utp) remote else listen ?: remote

  private companion object {
    const val MAX_TRACKED = 4096
    /** A target is asked for again only after this long. */
    const val RETRY_MS = 5 * 60_000L
  }
}
