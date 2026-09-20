package com.linroid.ketch.api.torrent

import kotlinx.serialization.Serializable

/**
 * Revision within one backend state incarnation. The opaque [epoch] changes whenever the backend
 * cannot preserve monotonic revisions (including restoring an older catalog). Counters never wrap.
 * Epochs cannot be ordered; a different epoch requires an authoritative reconnect snapshot.
 */
@Serializable
data class TorrentRevision(val epoch: String, val sequence: Long) {
  init {
    require(epoch.isNotBlank() && epoch.length <= 128)
    require(sequence >= 0)
  }
}

/** Result of comparing an incoming snapshot/event against the current task view. */
enum class TorrentRevisionDecision {
  APPLY,
  IGNORE,
  RESYNC,
}

/**
 * Decide whether to apply a revision from the current connection generation.
 *
 * A delta requires an exact predecessor; full snapshots may jump forward. Duplicate/older data
 * never replaces newer state. A changed epoch requires reconnect initialization, not implicit
 * replacement. The caller must separately discard responses from obsolete connection generations.
 */
fun TorrentRevision.compareIncoming(
  incoming: TorrentRevision,
  predecessor: TorrentRevision? = null,
): TorrentRevisionDecision = when {
  incoming.epoch != epoch -> TorrentRevisionDecision.RESYNC
  incoming.sequence <= sequence -> TorrentRevisionDecision.IGNORE
  predecessor != null && predecessor != this -> TorrentRevisionDecision.RESYNC
  else -> TorrentRevisionDecision.APPLY
}

/**
 * Optimistic concurrency and retry identity for one mutation of an existing task.
 *
 * The backend scopes [idempotencyKey] to the authenticated principal and task. An exact retry
 * returns
 * the original outcome; reuse with a different command fails. A new command requires the current
 * [expectedRevision]. Check the retry ledger before checking the revision. Long-running operations
 * return their operation ID; accepting a command does not imply completion.
 */
@Serializable
data class TorrentCommandContext(
  val idempotencyKey: String,
  val expectedRevision: TorrentRevision,
) {
  init {
    require(idempotencyKey.isNotBlank() && idempotencyKey.length <= 128)
  }
}
