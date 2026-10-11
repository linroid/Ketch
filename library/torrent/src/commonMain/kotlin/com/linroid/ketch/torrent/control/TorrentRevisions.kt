package com.linroid.ketch.torrent.control

import com.linroid.ketch.torrent.api.TorrentCommandError
import com.linroid.ketch.torrent.api.TorrentCommandException
import com.linroid.ketch.torrent.api.TorrentRevision
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.uuid.Uuid

/**
 * The revisions of the torrent tasks one [com.linroid.ketch.core.Ketch] instance publishes. The
 * [epoch] is new for every instance, so revisions from before a restart never compare to later
 * ones. Within it, a task's sequence grows whenever its published content changes, and its
 * control sequence marks the last control change (a selection or a seeding change): commands
 * only conflict with revisions older than that, never with progress.
 */
internal class TorrentRevisions(val epoch: String = Uuid.random().toString()) {
  private val mutex = Mutex()
  private val tasks = mutableMapOf<String, Entry>()

  /** The control state a revision describes: the selection generation and the seeding intent. */
  data class Control(val generation: Long, val seeding: Boolean)

  private class Entry(
    var sequence: Long,
    var controlSequence: Long,
    var content: Any?,
    var control: Control?,
    /**
     * The control before the last [markControl], while the task's record may still hold it: a
     * command reserves its revision before it saves the record, so content read in between
     * shows this control without anything having changed it again.
     */
    var superseded: Control? = null,
  )

  /**
   * The revision of [taskId]'s [content], which includes [control]: the current one when the
   * content did not change, else the next. A changed [control] that no command announced with
   * [markControl] moves the control sequence too; the control a command just replaced does not,
   * until content shows the command's own.
   */
  suspend fun publish(taskId: String, content: Any, control: Control): TorrentRevision =
    mutex.withLock {
      val entry = tasks[taskId]
      if (entry == null) {
        tasks[taskId] = Entry(0, 0, content, control)
        return@withLock revision(0)
      }
      if (control == entry.control) entry.superseded = null
      if (entry.content != content) {
        entry.sequence++
        entry.content = content
        if (entry.control != control && control != entry.superseded) {
          entry.controlSequence = entry.sequence
          entry.control = control
          entry.superseded = null
        }
      }
      revision(entry.sequence)
    }

  /**
   * Reserves the revision of a command that leaves [taskId] at [control]: the next sequence,
   * which becomes its control sequence. The content the command publishes next keeps it.
   */
  suspend fun markControl(taskId: String, control: Control): TorrentRevision = mutex.withLock {
    val entry = tasks.getOrPut(taskId) { Entry(0, 0, null, null) }
    if (entry.control != control) entry.superseded = entry.control
    entry.sequence++
    entry.controlSequence = entry.sequence
    entry.control = control
    revision(entry.sequence)
  }

  /**
   * Throws [TorrentCommandError.CONFLICT] with the current revision unless [expected] is of this
   * epoch, no older than [taskId]'s last control change and no newer than its current revision.
   */
  suspend fun requireCurrent(taskId: String, expected: TorrentRevision): Unit = mutex.withLock {
    val entry = tasks[taskId]
    val sequence = entry?.sequence ?: 0
    val current = expected.epoch == epoch && entry != null &&
      expected.sequence in entry.controlSequence..entry.sequence
    if (!current) {
      throw TorrentCommandException(
        error = TorrentCommandError.CONFLICT,
        message = "The download changed since that revision",
        currentRevision = revision(sequence),
      )
    }
  }

  /** Forgets [taskId], once it is removed. */
  suspend fun drop(taskId: String) {
    mutex.withLock { tasks.remove(taskId) }
  }

  private fun revision(sequence: Long) = TorrentRevision(epoch, sequence)
}
