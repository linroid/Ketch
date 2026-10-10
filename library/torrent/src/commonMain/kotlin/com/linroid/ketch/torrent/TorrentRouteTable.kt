package com.linroid.ketch.torrent

import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import okio.ByteString
import okio.ByteString.Companion.toByteString

/** Runs under the engine mutex: must not suspend or block. True transfers [connection]. */
internal fun interface TorrentIncomingSink {
  fun accept(connection: TorrentConnection): Boolean
}

/**
 * 20-byte wire tags of live owners across the v1 and truncated-v2 spaces; a hybrid owner holds
 * one tag of each. Owned by the engine mutex, except [tags], a snapshot readable without it.
 */
@OptIn(ExperimentalAtomicApi::class)
internal class TorrentRouteTable {
  /** One owner's registration. Its tags stay reserved from [register] until [release]. */
  class Claim internal constructor(
    val key: String,
    val tags: List<ByteString>,
    val sink: TorrentIncomingSink,
  ) {
    /** False once removal began: lookups miss, but the tags cannot be claimed again yet. */
    var accepting: Boolean = true
      internal set
  }

  private val claims = mutableMapOf<ByteString, Claim>()
  private val snapshot = AtomicReference<Set<ByteString>>(emptySet())

  /** Immutable copy of the accepting tags, replaced on every change (PR5 matches `req2` here). */
  val tags: Set<ByteString> get() = snapshot.load()

  /**
   * Validates every tag, then inserts them all, so a collision leaves the table unchanged.
   * Closing claims still collide: their owner has not released its files yet.
   */
  fun register(key: String, tags: List<ByteString>, sink: TorrentIncomingSink): Claim {
    require(tags.size in 1..2 && tags.all { it.size == 20 } && tags.toSet().size == tags.size) {
      "Ambiguous wire tags"
    }
    check(tags.none { it in claims }) { "Torrent already has an active owner" }
    val claim = Claim(key, tags.toList(), sink)
    tags.forEach { claims[it] = claim }
    publish()
    return claim
  }

  /** The accepting claim that owns [tag], if any. */
  fun lookup(tag: ByteString): Claim? = claims[tag]?.takeIf { it.accepting }

  /** Removal began: new connections are refused while the owner closes. */
  fun stopAccepting(claim: Claim) {
    if (!claim.accepting) return
    claim.accepting = false
    publish()
  }

  /** Removal finished: the tags may be claimed again. */
  fun release(claim: Claim) {
    claim.accepting = false
    claim.tags.forEach { if (claims[it] === claim) claims.remove(it) }
    publish()
  }

  fun clear() {
    claims.values.forEach { it.accepting = false }
    claims.clear()
    publish()
  }

  private fun publish() {
    snapshot.store(claims.filterValues { it.accepting }.keys.toSet())
  }

  companion object {
    /** The wire tags an owner of [identity] answers: v1 hash, truncated v2 hash, or both. */
    fun tagsOf(identity: TorrentIdentity): List<ByteString> = listOfNotNull(
      identity.v1?.toBytes()?.toByteString(),
      identity.v2?.wireBytes()?.toByteString(),
    )
  }
}
