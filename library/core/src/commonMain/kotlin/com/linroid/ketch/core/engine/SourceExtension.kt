package com.linroid.ketch.core.engine

import com.linroid.ketch.api.ControlKey
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.Interest
import com.linroid.ketch.api.KetchApi
import com.linroid.ketch.core.task.ControlCommand
import com.linroid.ketch.core.task.ControlLedgerException
import com.linroid.ketch.core.task.ControlOutcome
import com.linroid.ketch.core.task.TaskControl
import com.linroid.ketch.core.task.TaskRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow
import kotlin.time.Duration

/**
 * What a [DownloadSource] adds to the [com.linroid.ketch.core.Ketch] that runs it beyond
 * downloading, through [DownloadSource.attach]: typed controllers that
 * [com.linroid.ketch.api.KetchApi.controller] returns, the interests of its module that
 * [com.linroid.ketch.api.KetchApi.subscribe] follows, and hooks into the lifecycle of the tasks.
 * Ketch calls the hooks on its own coordination scope; long work belongs in [SourceHost.scope].
 */
interface SourceExtension {
  /** This extension's controller for [key], or `null` when it offers none. */
  fun <T : Any> controller(key: ControlKey<T>): T? = null

  /**
   * What this extension reports for [interest], about every [interval], or `null` when it does
   * not know it.
   */
  fun <T> subscribe(interest: Interest<T>, interval: Duration): Flow<T>? = null

  /** Called once, when [com.linroid.ketch.core.Ketch.start] has loaded the saved tasks. */
  suspend fun start() {}

  /** Called each time a task of this source becomes [DownloadState.Completed]. */
  suspend fun taskCompleted(task: DownloadTask) {}

  /**
   * Called when a completed task of this source chose [fileIds], a selection that adds no files,
   * so it stays completed: what the source shares of it follows the new selection.
   */
  suspend fun selectionChanged(task: DownloadTask, fileIds: Set<String>) {}
}

/**
 * What a [SourceExtension] may see and do of the tasks of the
 * [com.linroid.ketch.core.Ketch] that runs its source. Ketch owns the task records: an extension
 * changes them only through these calls, each of which saves its change in one record write.
 */
interface SourceHost {
  /** Every task of this Ketch instance. */
  val tasks: StateFlow<List<DownloadTask>>

  /** Ketch's coordination scope, cancelled when Ketch closes. */
  val scope: CoroutineScope

  /**
   * The saved record of [task], or `null` for a task that is not one of Ketch's in-process
   * tasks. A task removed since keeps its last record; [tasks] tells whether it is still there.
   */
  fun record(task: DownloadTask): TaskRecord?

  /**
   * Where a session the extension starts for [task] outside a download, such as one sharing its
   * finished files, reports its live connections ([Interest.Connections]).
   */
  fun connections(task: DownloadTask): ConnectionReporter = ConnectionReporter.None

  /**
   * Chooses the files [task] downloads, like [DownloadTask.selectFiles], guarded by [command]:
   * the task's retry ledger answers an exact retry and records the outcome with the selection.
   *
   * @throws ControlLedgerException when the ledger refuses the command
   * @throws IllegalArgumentException for files the task's source does not list
   * @throws IllegalStateException when the task's state allows no selection
   */
  suspend fun selectFiles(
    task: DownloadTask,
    fileIds: Set<String>,
    command: ControlCommand,
  ): ControlOutcome

  /**
   * Saves whether the completed [task] is shared whenever its source can
   * ([TaskControl.sharing]), guarded by [command] like [selectFiles]. Starting or stopping the
   * sharing itself is left to the caller.
   *
   * @throws ControlLedgerException when the ledger refuses the command
   * @throws IllegalStateException when the task is not completed
   */
  suspend fun setSharing(
    task: DownloadTask,
    sharing: Boolean,
    command: ControlCommand,
  ): ControlOutcome

  /**
   * Shows whether the completed [task] is shared now, as [DownloadState.Completed.sharing]. A
   * task in another state is left alone.
   */
  suspend fun showSharing(task: DownloadTask, sharing: Boolean)

  /**
   * Saves whether the completed [task] is shared again after a restart ([TaskControl.sharing]),
   * without a command. Saving `true` for a task that is no longer completed does nothing, and
   * nothing is saved unless [onlyIf], checked under the task's control lock, holds: a save that
   * waited for the lock must not undo a command that changed the intent meanwhile.
   */
  suspend fun saveSharingIntent(
    task: DownloadTask,
    sharing: Boolean,
    onlyIf: () -> Boolean = { true },
  )
}
