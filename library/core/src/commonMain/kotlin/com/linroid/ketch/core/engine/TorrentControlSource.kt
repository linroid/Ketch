package com.linroid.ketch.core.engine

import com.linroid.ketch.api.ResolvedSource
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.torrent.TorrentActivity
import kotlinx.coroutines.flow.StateFlow

/**
 * Torrent controls a [DownloadSource] offers beyond downloading, which Ketch's
 * [com.linroid.ketch.api.torrent.TorrentController] is built on: inspecting the files and the
 * live session of a task, and sharing completed tasks. Ketch owns the task records; a source
 * only reports and acts on its sessions.
 */
interface TorrentControlSource {
  /**
   * The [com.linroid.ketch.api.torrent.TorrentCapability] wire names this source carries out
   * now, besides `inspect`, which every implementation offers. It may change at runtime, such
   * as when sharing is switched off.
   */
  val torrentCapabilities: Set<String>

  /** IDs of the tasks whose completed content is being shared right now. */
  val seedingTaskIds: StateFlow<Set<String>>

  /** Whether [com.linroid.ketch.core.Ketch.start] shares again the tasks that were sharing. */
  val restoresSeeding: Boolean

  /**
   * Every content file of a task in metainfo order, from metadata the source holds in
   * [resumeState] or, before the task started, in [resolved]; `null` while the files are not
   * known, such as for a magnet still looking for its metadata.
   */
  suspend fun torrentFiles(
    resumeState: SourceResumeState?,
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
class LiveTorrent(
  val activity: TorrentActivity,
  val receivedPayloadBytes: Long?,
  val uploadedPayloadBytes: Long?,
  val uploadBytesPerSecond: Long?,
)

/**
 * A completed task to share with [TorrentControlSource.startSeeding].
 *
 * @property url the task's URL
 * @property resumeState the task's saved source state
 * @property outputPath where the task saved its files
 * @property selectedFileIds the task's files; empty for every file
 */
class SeedingTask(
  val taskId: String,
  val url: String,
  val resumeState: SourceResumeState,
  val outputPath: String,
  val selectedFileIds: Set<String>,
)

/** How an attempt to share a completed task ended, or why it would not start. */
enum class SeedingOutcome {
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
