package com.linroid.ketch.api.torrent

import kotlinx.coroutines.flow.Flow

/**
 * Backend-owned torrent inspection and capability negotiation.
 *
 * Obtain this optional controller from KetchApi. A null controller means the backend does not
 * expose this protocol; it does not imply that legacy torrent downloads are unavailable.
 * Runtime implementations and mutation commands are introduced with their roadmap capabilities.
 * Implementations advertise only features they can execute, never planned features.
 *
 * Commands take a [TorrentCommandContext]. Its [TorrentCommandContext.expectedRevision] is
 * selection-scoped: it must be at least the revision of the task's last control change (a
 * selection or seeding change) within the same epoch, so progress-only revisions never make a
 * command conflict. An exact retry with the same idempotency key returns the first outcome while
 * the task keeps it in its retry ledger (32 entries per task, for 15 minutes); the same key with
 * another command fails with [TorrentCommandError.KEY_REUSED]. Failures throw
 * [TorrentCommandException]: [TorrentCommandError.CONFLICT] with the current revision for a stale
 * revision, [TorrentCommandError.STALE_CURSOR] for a page cursor of an older selection or another
 * order, and [TorrentCommandError.RESOURCE_EXHAUSTED] when a bound is reached.
 *
 * The default implementations of the commands throw [TorrentCommandException] with
 * [TorrentCommandError.UNSUPPORTED].
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

  /**
   * One page of every content file of the task, sorted by [order] ([descending] reverses it;
   * ties keep metainfo order), or `null` for unknown and non-torrent tasks. Entries carry no
   * progress, so a page never mixes revisions. A cursor binds the task, its selection generation
   * and the order: one used after the selection changed or with another order fails.
   */
  suspend fun files(
    taskId: String,
    page: TorrentPageRequest = TorrentPageRequest(),
    order: TorrentFileOrder = TorrentFileOrder.TORRENT,
    descending: Boolean = false,
  ): TorrentFilePage? = throw TorrentCommandException(
    error = TorrentCommandError.UNSUPPORTED,
    message = "Torrent file pages are unavailable",
  )

  /**
   * Chooses the files the task downloads, like [com.linroid.ketch.api.DownloadTask.selectFiles],
   * guarded by [context].
   */
  suspend fun select(
    taskId: String,
    fileIds: Set<String>,
    context: TorrentCommandContext,
  ): TorrentCommandResult = throw TorrentCommandException(
    error = TorrentCommandError.UNSUPPORTED,
    message = "File selection is unavailable",
  )

  /**
   * Starts ([seeding] `true`) or stops sharing a completed task's content with other peers, and
   * remembers the choice, guarded by [context].
   */
  suspend fun setSeeding(
    taskId: String,
    seeding: Boolean,
    context: TorrentCommandContext,
  ): TorrentCommandResult = throw TorrentCommandException(
    error = TorrentCommandError.UNSUPPORTED,
    message = "Seeding control is unavailable",
  )
}
