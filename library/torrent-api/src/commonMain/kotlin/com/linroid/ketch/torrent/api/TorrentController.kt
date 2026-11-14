package com.linroid.ketch.torrent.api

import com.linroid.ketch.api.ControlKey

/**
 * Backend-owned torrent inspection and capability negotiation.
 *
 * Obtain this optional controller with `ketch.controller(TorrentController)`
 * ([com.linroid.ketch.api.KetchApi.controller]). A null controller means the backend does not expose
 * this protocol; it does not imply that torrent downloads are unavailable. A remote backend's
 * controller fails closed against servers whose features lack [TorrentFeatures.CONTROL].
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
  /** The key [com.linroid.ketch.api.KetchApi.controller] looks torrent controllers up with. */
  companion object Key : ControlKey<TorrentController> {
    override val name: String = "torrent"
  }

  /** Negotiate before subscribing or issuing feature-specific requests. */
  suspend fun capabilities(): TorrentCapabilities

  /**
   * Get an authoritative summary, or null if the task no longer exists or is not a torrent.
   * Callers must merge concurrent HTTP/event results using [TorrentRevision.compareIncoming].
   */
  suspend fun snapshot(taskId: String): TorrentSnapshot?

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

  /**
   * Downloads the task's pieces in order ([sequential] `true`), so a file can be played while it
   * downloads, or rarest first; applies at once and is kept with the task. Advertised as
   * [TorrentCapability.SEQUENTIAL].
   */
  suspend fun setSequential(taskId: String, sequential: Boolean): Unit =
    throw TorrentCommandException(
      error = TorrentCommandError.UNSUPPORTED,
      message = "Sequential download is unavailable",
    )

  /**
   * The task's magnet link and info hashes, with its `.torrent` file when [metainfo] is true, or
   * `null` for unknown and non-torrent tasks. A private torrent has no magnet link. Fails with
   * [TorrentCommandError.METADATA_UNAVAILABLE] while nothing identifies the torrent yet, as
   * before a `.torrent` link is fetched. Advertised as [TorrentCapability.EXPORT].
   */
  suspend fun export(taskId: String, metainfo: Boolean = false): TorrentExport? =
    throw TorrentCommandException(
      error = TorrentCommandError.UNSUPPORTED,
      message = "Torrent export is unavailable",
    )

  /**
   * The files the task downloads before its others, which [files] entries mark too
   * ([TorrentFileEntry.priority]); empty for the usual order. Fails with
   * [TorrentCommandError.NOT_FOUND] for unknown and non-torrent tasks. Advertised as
   * [TorrentCapability.FILE_PRIORITY].
   */
  suspend fun priorityFiles(taskId: String): Set<String> = throw TorrentCommandException(
    error = TorrentCommandError.UNSUPPORTED,
    message = "File priorities are unavailable",
  )

  /**
   * Sets the files [priorityFiles] answers: the ones the task downloads before its others, at
   * once and whenever it runs; an empty set goes back to the usual order. It orders the download
   * and selects nothing: a file outside the selection is not downloaded for it. Fails with
   * [TorrentCommandError.INVALID_INPUT] for an ID the torrent does not list. Advertised as
   * [TorrentCapability.FILE_PRIORITY].
   */
  suspend fun setPriorityFiles(taskId: String, fileIds: Set<String>): Unit =
    throw TorrentCommandException(
      error = TorrentCommandError.UNSUPPORTED,
      message = "File priorities are unavailable",
    )

  /**
   * Checks the task's files against the torrent's hashes again and downloads what is missing or
   * changed: a completed task runs again from what it has (it stops seeding meanwhile, and
   * seeds again once it completes if it was meant to), and a downloading one starts over its
   * check. A task that is paused, queued or failed is checked when it next starts, as every
   * start checks the files, so the command changes nothing for it. Fails with
   * [TorrentCommandError.NOT_FOUND] for unknown and non-torrent tasks and
   * [TorrentCommandError.INVALID_STATE] for a canceled one. Advertised as
   * [TorrentCapability.RECHECK].
   */
  suspend fun recheck(taskId: String): Unit = throw TorrentCommandException(
    error = TorrentCommandError.UNSUPPORTED,
    message = "Rechecking is unavailable",
  )

  /**
   * The task's own trackers in tier order and how each answers, or `null` for unknown and
   * non-torrent tasks: live while the torrent runs or seeds, and [TorrentTrackerState.IDLE]
   * otherwise. The trackers the backend adds to public torrents are not listed. Fails with
   * [TorrentCommandError.METADATA_UNAVAILABLE] while the torrent's trackers are not known yet.
   * Advertised as [TorrentCapability.TRACKERS].
   */
  suspend fun trackers(taskId: String): List<TorrentTracker>? =
    throw TorrentCommandException(
      error = TorrentCommandError.UNSUPPORTED,
      message = "Tracker status is unavailable",
    )

  /**
   * Changes the task's own trackers as [edit] says and answers them as [trackers] does. The
   * change stays with the task, and a torrent that downloads or seeds starts again to announce
   * to them. Fails with [TorrentCommandError.NOT_FOUND] for unknown and non-torrent tasks, with
   * [TorrentCommandError.INVALID_INPUT] for a URL the backend cannot announce to or more
   * trackers than a torrent may have, and with [TorrentCommandError.METADATA_UNAVAILABLE] while
   * the torrent's trackers are not known yet. Advertised as [TorrentCapability.TRACKER_EDIT].
   */
  suspend fun editTrackers(taskId: String, edit: TorrentTrackerEdit): List<TorrentTracker> =
    throw TorrentCommandException(
      error = TorrentCommandError.UNSUPPORTED,
      message = "Editing trackers is unavailable",
    )

  /**
   * Whether peers can connect to the backend's torrent engine, through how many NAT layers its
   * port is forwarded, and whether it punches through NATs; advertised as
   * [TorrentCapability.REACHABILITY].
   */
  suspend fun reachability(): TorrentReachability = throw TorrentCommandException(
    error = TorrentCommandError.UNSUPPORTED,
    message = "Reachability is unavailable",
  )

  /**
   * The settings every torrent of the backend runs with; advertised as
   * [TorrentCapability.SETTINGS].
   */
  suspend fun settings(): TorrentEngineSettings = throw TorrentCommandException(
    error = TorrentCommandError.UNSUPPORTED,
    message = "Torrent settings are unavailable",
  )

  /**
   * Changes the settings [change] sets, keeping the others, and returns the settings every
   * torrent runs with now: the upload mode and limit, encryption, uTP, port mapping and local
   * discovery apply at once, extra trackers and tracker lists to torrents as they start or
   * resume. The backend's host saves them, so they outlive a restart. Advertised as
   * [TorrentCapability.SETTINGS]; a malformed change, such as too many trackers, fails with
   * [TorrentCommandError.INVALID_INPUT].
   */
  suspend fun updateSettings(change: TorrentSettingsChange): TorrentEngineSettings =
    throw TorrentCommandException(
      error = TorrentCommandError.UNSUPPORTED,
      message = "Torrent settings are unavailable",
    )
}
