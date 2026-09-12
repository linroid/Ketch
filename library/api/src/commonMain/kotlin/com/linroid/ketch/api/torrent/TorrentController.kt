package com.linroid.ketch.api.torrent

import kotlinx.coroutines.flow.Flow

/**
 * Backend-owned torrent inspection and capability negotiation.
 *
 * Obtain this optional controller from KetchApi. A null controller means the backend does not
 * expose this protocol; it does not imply that legacy torrent downloads are unavailable.
 * Runtime implementations and mutation commands are introduced with their roadmap capabilities.
 * Implementations advertise only features they can execute, never planned features.
 */
interface TorrentController {
  /** Negotiate before subscribing or issuing feature-specific requests. */
  suspend fun capabilities(): TorrentCapabilities

  /**
   * Get an authoritative summary, or null if the task no longer exists or is not a torrent.
   * Callers must merge concurrent HTTP/event results using [TorrentRevision.compareIncoming].
   */
  suspend fun snapshot(taskId: String): TorrentSnapshot?

  /**
   * Observe full snapshots for one task, starting with its current value. Null is a tombstone and
   * completes the stream. Unknown tasks emit null and complete. The backend bounds subscriptions;
   * each slow subscriber receives the latest full snapshot, not an unbounded queue of updates.
   *
   * The adapter cancels old subscriptions before reconnecting, obtains a new authoritative
   * snapshot,
   * and discards all late responses from the old connection generation. Revisions within an epoch
   * never decrease. Transport errors terminate the stream and require explicit reconnect.
   */
  fun observe(taskId: String): Flow<TorrentSnapshot?>
}
