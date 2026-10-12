package com.linroid.ketch.torrent.control

import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.core.engine.ConnectionReporter
import com.linroid.ketch.core.engine.SourceState
import com.linroid.ketch.torrent.api.TorrentActivity
import kotlinx.coroutines.flow.StateFlow

/**
 * Torrent controls a download source offers beyond downloading, which [KetchTorrentController]
 * and [TorrentControls] are built on: inspecting the files and the live session of a task, and
 * sharing completed tasks. Ketch owns the task records; a source only reports and acts on its
 * sessions.
 */
internal interface TorrentControlSource {
  /**
   * The [com.linroid.ketch.torrent.api.TorrentCapability] wire names this source carries out
   * now, besides `inspect`, which every implementation offers. It may change at runtime, such
   * as when sharing is switched off.
   */
  val torrentCapabilities: Set<String>

  /** IDs of the tasks whose completed content is being shared right now. */
  val seedingTaskIds: StateFlow<Set<String>>

  /** Whether [com.linroid.ketch.core.Ketch.start] shares again the tasks that were sharing. */
  val restoresSeeding: Boolean

  /**
   * Every content file of a task in metainfo order, from the resolution the task kept, else the
   * request's ([resolved]), or as older builds saved it in [sourceState]; `null` while the files
   * are not known, such as for a magnet still looking for its metadata.
   */
  suspend fun torrentFiles(
    sourceState: SourceState?,
    resolved: ResolvedSource?,
  ): List<SourceFile>?

  /** What the task's session in this process is doing, or `null` when it has none. */
  suspend fun liveTorrent(taskId: String): LiveTorrent?

  /**
   * Why [startSeeding] would not share the task now, or `null` when it could start. Has no side
   * effects.
   */
  suspend fun seedingAvailability(taskId: String): SeedingOutcome?

  /**
   * Starts sharing a completed task, checking its files first, and suspends until it shares or
   * stops trying.
   */
  suspend fun startSeeding(task: SeedingTask): SeedingOutcome

  /**
   * Stops the task's seeding session, if any, saving its state first; a download in progress is
   * left alone.
   */
  suspend fun stopSeeding(taskId: String)

  /**
   * Applies [fileIds], a completed task's new selection that adds no files, to the task's
   * seeding session, if any, so it shares and announces that selection and a removed file it can
   * no longer read does not stop it. Returns whether a session took the change.
   */
  suspend fun changeSeedingSelection(taskId: String, fileIds: Set<String>): Boolean = false
}

/**
 * A task's live torrent session.
 *
 * @property activity what the session is doing
 * @property receivedPayloadBytes payload received from peers by this session, or `null` when not
 *   tracked
 * @property uploadedPayloadBytes payload sent to peers by this session, or `null` when not
 *   tracked
 * @property uploadBytesPerSecond the current upload speed, or `null` when not tracked
 */
internal class LiveTorrent(
  val activity: TorrentActivity,
  val receivedPayloadBytes: Long?,
  val uploadedPayloadBytes: Long?,
  val uploadBytesPerSecond: Long?,
)

/**
 * A completed task to share with [TorrentControlSource.startSeeding].
 *
 * @property url the task's URL
 * @property sourceState the task's saved source state
 * @property resolved the resolution the task kept, which holds its metainfo
 * @property outputPath where the task saved its files
 * @property selectedFileIds the task's files; empty for every file
 * @property connections opens the task's live network connections while it seeds, as
 *   [DownloadContext.connections] does while it downloads
 */
internal class SeedingTask(
  val taskId: String,
  val url: String,
  val sourceState: SourceState,
  val resolved: ResolvedSource?,
  val outputPath: String,
  val selectedFileIds: Set<String>,
  val connections: ConnectionReporter = ConnectionReporter.None,
)

/** How an attempt to share a completed task ended, or why it would not start. */
internal enum class SeedingOutcome {
  /** The task is being shared. */
  SEEDING,

  /** Every active torrent slot is taken. */
  NO_SLOT,

  /** The upload setting does not share completed torrents. */
  POLICY_OFF,

  /** The task already has a session in this process. */
  ALREADY_ACTIVE,

  /** The task's files changed on disk since it completed, so it cannot be shared as it is. */
  CHANGED_ON_DISK,

  /** The source cannot share tasks. */
  UNSUPPORTED,

  /** Sharing failed for another reason. */
  FAILED,
}
