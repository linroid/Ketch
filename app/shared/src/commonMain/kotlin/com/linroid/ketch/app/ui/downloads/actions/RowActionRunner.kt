package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalUriHandler
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.feedback.MessageAction
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.priorityText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.platform.FileActions
import com.linroid.ketch.app.platform.rememberFileActions
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.state.speedLimitText
import com.linroid.ketch.app.state.taskActions
import com.linroid.ketch.app.state.waitsInQueue
import com.linroid.ketch.app.ui.dialog.RemovalPlan
import com.linroid.ketch.app.ui.list.CopiedText
import com.linroid.ketch.app.ui.list.RowCommands
import com.linroid.ketch.app.ui.list.TaskCommand
import com.linroid.ketch.app.ui.list.outputFile
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_try_again
import ketch.app.shared.generated.resources.action_undo
import ketch.app.shared.generated.resources.count_downloads
import ketch.app.shared.generated.resources.downloads_batch_connections
import ketch.app.shared.generated.resources.downloads_batch_connections_auto
import ketch.app.shared.generated.resources.downloads_batch_failed
import ketch.app.shared.generated.resources.downloads_batch_limited
import ketch.app.shared.generated.resources.downloads_batch_paused
import ketch.app.shared.generated.resources.downloads_batch_priority
import ketch.app.shared.generated.resources.downloads_batch_rescheduled
import ketch.app.shared.generated.resources.downloads_batch_resumed
import ketch.app.shared.generated.resources.downloads_batch_retrying
import ketch.app.shared.generated.resources.downloads_batch_shared
import ketch.app.shared.generated.resources.downloads_batch_unlimited
import ketch.app.shared.generated.resources.downloads_remove_failed
import ketch.app.shared.generated.resources.downloads_skipped_already_paused
import ketch.app.shared.generated.resources.downloads_skipped_canceled
import ketch.app.shared.generated.resources.downloads_skipped_failed
import ketch.app.shared.generated.resources.downloads_skipped_finished
import ketch.app.shared.generated.resources.downloads_skipped_paused
import ketch.app.shared.generated.resources.downloads_skipped_running
import ketch.app.shared.generated.resources.downloads_skipped_scheduled
import ketch.app.shared.generated.resources.downloads_trash_failed
import ketch.app.shared.generated.resources.downloads_trash_failed_many
import ketch.app.shared.generated.resources.downloads_trash_its_file
import ketch.app.shared.generated.resources.downloads_trash_moved
import ketch.app.shared.generated.resources.downloads_trash_removed
import ketch.app.shared.generated.resources.downloads_trash_stay
import ketch.app.shared.generated.resources.downloads_trash_stays
import ketch.app.shared.generated.resources.downloads_trash_their_files
import ketch.app.shared.generated.resources.feedback_undo_remove
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope
import org.jetbrains.compose.resources.PluralStringResource

/**
 * A dialog a row action asks for before it runs.
 *
 * @property rows the rows the action runs on.
 */
internal sealed interface RowDialog {
  val rows: List<TaskRow>

  /**
   * Remove, with the box that also moves the files to the Trash or deletes them.
   *
   * @property withFiles whether that box starts checked, as with ⇧⌫.
   */
  data class Remove(override val rows: List<TaskRow>, val withFiles: Boolean) : RowDialog

  /** Stop and discard progress. */
  data class Discard(override val rows: List<TaskRow>) : RowDialog

  /** A speed limit typed by hand. */
  data class CustomSpeed(override val rows: List<TaskRow>) : RowDialog

  /** A start date and time picked from a calendar. */
  data class PickStart(override val rows: List<TaskRow>) : RowDialog
}

/**
 * An action over several rows.
 *
 * @property action what runs.
 * @property rows the rows among the targets it applies to, in display order.
 */
internal data class BatchAction(val action: RowAction, val rows: List<TaskRow>)

/**
 * Runs the [RowAction]s of the Downloads list on one row or on every selected row, and holds the
 * dialogs they ask for, which [RowActionDialogs] shows.
 *
 * One row runs through [commands], as its trailing button does. Several rows run as one batch
 * whose outcome is a single message, such as "Paused 2 · 1 already finished", and whose failures
 * offer Try again for the rows that failed. Removing, clearing and discarding progress go through
 * the Undo of [AppState].
 *
 * @param scope runs file checks; [commands]' own by default. Batches run in the app's scope
 *   instead, so leaving the screen never stops one with half the rows done, and Try again still
 *   works.
 */
@Stable
internal class RowActionRunner(
  val commands: RowCommands,
  private val scope: CoroutineScope = commands.scope,
) {
  /** The app the rows belong to. */
  val state: AppState = commands.state

  /** Opens, reveals and trashes downloaded files; `null` where they are out of reach. */
  val files: FileActions? = commands.files

  private val log = KetchLogger("RowActions")
  private val missing = mutableStateMapOf<TaskKey, Boolean>()

  /** The dialog an action asked for, or `null`. */
  var dialog: RowDialog? by mutableStateOf(null)
    private set

  /** Closes [dialog]. */
  fun dismissDialog() {
    dialog = null
  }

  /** Whether [row] finished on this device but its file has since been moved or deleted. */
  fun isFileMissing(row: TaskRow): Boolean = missing[row.key] == true

  /**
   * Checks in the background whether a completed local [row] still has its file, as the menu
   * and hover actions do before they offer Open; see [isFileMissing].
   */
  fun checkFile(row: TaskRow) {
    val files = files ?: return
    val path = row.outputFile ?: return
    if (row.device.capabilities.isRemote) return
    scope.launch {
      catchingUnlessCancelled { files.exists(path) }
        .onSuccess { exists -> missing[row.key] = !exists }
        .onFailure { e ->
          log.d { "Couldn't check the file of taskId=${row.key.taskId}: ${e.describeCauses()}" }
        }
    }
  }

  /** Checks the file of every completed local row among [rows]; see [checkFile]. */
  fun checkFiles(rows: List<TaskRow>) {
    rows.forEach(::checkFile)
  }

  /**
   * Clears the finished downloads of the shown devices whose files have been moved or deleted,
   * checking each file again first; see [AppState.clearMissing]. The files of [rows], those the
   * caller counted, are checked again here too, so one put back since reads as present.
   */
  fun clearMissing(rows: List<TaskRow>) {
    val files = files ?: return
    checkFiles(rows)
    state.clearMissing(files)
  }

  /** Whether removed files of [row] can go to the Trash here instead of being deleted. */
  fun canTrash(row: TaskRow): Boolean =
    files?.canTrash == true && !row.device.capabilities.isRemote

  /** The actions [row] offers here, in menu order, its primary one included. */
  fun menu(row: TaskRow): List<RowAction> = actionsOf(row).menu
    .map { if (it == RowAction.RemoveAndDelete && canTrash(row)) RowAction.RemoveAndTrash else it }
    .filter { commands.canRun(it, row) }

  /** The hover buttons of [row] that can run here, before "⋯". */
  fun hover(row: TaskRow): List<RowAction> =
    actionsOf(row).hover.filter { commands.canRun(it, row) }

  /** The row's trailing action: its primary one, or the first fix of its failure that can run. */
  fun primary(row: TaskRow): RowAction? {
    val primary = actionsOf(row).primary ?: return null
    if (commands.canRun(primary, row)) return primary
    return row.content.error?.secondary?.firstOrNull { commands.canRun(it, row) }
  }

  /**
   * The actions that apply to at least one of [rows], in menu order, each with the rows it
   * applies to. Retry covers failed and canceled rows, which start over when their progress
   * cannot be reused; Download again covers finished ones.
   */
  fun batch(rows: List<TaskRow>): List<BatchAction> {
    val menus = rows.associateWith(::menu)
    return BATCH_ORDER.mapNotNull { action ->
      val applies = rows.filter { row -> appliesInBatch(action, row, menus.getValue(row)) }
      applies.takeIf { it.isNotEmpty() }?.let { BatchAction(action, it) }
    }
  }

  /**
   * Runs [action] on [rows]. One row behaves like its own menu; several run as a batch. Actions
   * that pick a value, such as [RowAction.SpeedLimit], do nothing here: their submenus call
   * [setSpeedLimit] and the like. Actions that confirm first open [dialog].
   */
  fun run(action: RowAction, rows: List<TaskRow>) {
    if (rows.size > 1) {
      runBatch(action, rows)
      return
    }
    val row = rows.singleOrNull() ?: return
    when (action) {
      RowAction.Details -> inspect(row.key)
      RowAction.StopAndDiscard -> dialog = RowDialog.Discard(rows)
      RowAction.RemoveAndTrash, RowAction.RemoveAndDelete -> dialog = RowDialog.Remove(rows, true)
      else -> commands.run(action, row)
    }
  }

  /** Opens the Remove dialog for [rows]; [withFiles] checks its box, as ⇧⌫ does. */
  fun requestRemove(rows: List<TaskRow>, withFiles: Boolean) {
    if (rows.isNotEmpty()) dialog = RowDialog.Remove(rows, withFiles)
  }

  /** Asks for a speed limit to type for [rows]. */
  fun requestCustomSpeed(rows: List<TaskRow>) {
    dialog = RowDialog.CustomSpeed(rows)
  }

  /** Asks for a start date and time for [rows]. */
  fun requestStartTime(rows: List<TaskRow>) {
    dialog = RowDialog.PickStart(rows)
  }

  /** Shows [key] in the inspector. */
  fun inspect(key: TaskKey) {
    state.inspect(key)
  }

  /** Caps each of [rows] at [limit]. */
  fun setSpeedLimit(rows: List<TaskRow>, limit: SpeedLimit): Job {
    rows.singleOrNull()?.let { return commands.setSpeedLimit(it, limit) }
    val done = if (limit.isUnlimited) {
      { n: Int -> Res.plurals.downloads_batch_unlimited.text(n) }
    } else {
      { n: Int -> Res.plurals.downloads_batch_limited.text(n, n, speedLimitText(limit)) }
    }
    return launchBatch(rows, TaskCommand.SpeedLimit, done) { setSpeedLimit(limit) }
  }

  /** Splits [total] evenly between [rows], each capped at its share. */
  fun shareSpeed(rows: List<TaskRow>, total: SpeedLimit) {
    if (rows.isEmpty() || total.isUnlimited) return
    val share = SpeedLimit.of((total.bytesPerSecond / rows.size).coerceAtLeast(1))
    launchBatch(rows, TaskCommand.SpeedLimit, { n ->
      Res.plurals.downloads_batch_shared.text(n, n, speedLimitText(total))
    }) { setSpeedLimit(share) }
  }

  /** Gives each of [rows] [priority]. */
  fun setPriority(rows: List<TaskRow>, priority: DownloadPriority): Job {
    rows.singleOrNull()?.let { return commands.setPriority(it, priority) }
    return launchBatch(rows, TaskCommand.Priority, { n ->
      Res.plurals.downloads_batch_priority.text(n, n, priorityText(priority))
    }) { setPriority(priority) }
  }

  /**
   * Lets each of [rows] open [connections] connections, or peers for a torrent; 0 gives each
   * its device's default (Auto).
   */
  fun setConnections(rows: List<TaskRow>, connections: Int): Job {
    rows.singleOrNull()?.let { return commands.setConnections(it, connections) }
    return launchBatch(rows, TaskCommand.Connections, { n ->
      if (connections == 0) {
        Res.plurals.downloads_batch_connections_auto.text(n)
      } else {
        Res.plurals.downloads_batch_connections.text(n, n, connections)
      }
    }) { setConnections(connections) }
  }

  /** Starts each of [rows] at [schedule]. */
  fun reschedule(rows: List<TaskRow>, schedule: DownloadSchedule): Job {
    rows.singleOrNull()?.let { return commands.reschedule(it, schedule) }
    val done = counted(Res.plurals.downloads_batch_rescheduled)
    return launchBatch(rows, TaskCommand.Reschedule, done) {
      reschedule(schedule)
    }
  }

  /**
   * Adds [rows] to [target], where they start over; with [move] they leave this list once sent.
   * Rows whose cookies would go along ask first; see [AppState.sendTo].
   */
  fun sendTo(rows: List<TaskRow>, target: InstanceEntry, move: Boolean = false) {
    state.sendTo(rows.map { it.task }, target, move)
  }

  /**
   * Removes [rows] from the list once the Undo window ends. With [withFiles] their files go too:
   * finished files to the Trash when [RemovalPlan.trash] allows it, every other file deleted by
   * the device.
   */
  fun remove(rows: List<TaskRow>, withFiles: Boolean) {
    if (rows.isEmpty()) return
    val plan = RemovalPlan.of(rows, canTrash = rows.all(::canTrash))
    when {
      !withFiles || !plan.hasFiles -> state.remove(rows.map { it.task })
      plan.trash -> removeToTrash(rows)
      else -> state.remove(rows.map { it.task }, deleteFiles = true)
    }
  }

  /** Stops [rows] and discards their progress, with Undo. */
  fun discard(rows: List<TaskRow>) {
    state.cancel(rows.map { it.task })
  }

  private fun actionsOf(row: TaskRow) = taskActions(
    request = row.request,
    state = row.state,
    device = row.device,
    stalled = row.isStalled,
    starting = row.isStarting,
    fileMissing = isFileMissing(row),
  )

  private fun appliesInBatch(action: RowAction, row: TaskRow, menu: List<RowAction>): Boolean =
    when (action) {
      RowAction.Retry -> row.state is DownloadState.Failed || row.state is DownloadState.Canceled
      RowAction.DownloadAgain -> row.state is DownloadState.Completed && action in menu
      else -> action in menu
    }

  private fun runBatch(action: RowAction, rows: List<TaskRow>) {
    val applies = batch(rows).firstOrNull { it.action == action }?.rows ?: return
    when (action) {
      RowAction.Pause -> {
        launchBatch(applies, TaskCommand.Pause, counted(Res.plurals.downloads_batch_paused), rows) {
          pause()
        }
      }
      RowAction.Resume -> {
        val done = counted(Res.plurals.downloads_batch_resumed)
        launchBatch(applies, TaskCommand.Resume, done, rows) { resume() }
      }
      RowAction.Retry -> {
        val app = state
        val done = counted(Res.plurals.downloads_batch_retrying)
        launchBatch(applies, TaskCommand.Retry, done, rows) { app.retryInBatch(this) }
      }
      RowAction.StartNow -> state.startNow(applies.map { it.task })
      RowAction.DownloadAgain -> state.redownload(applies.map { it.task })
      RowAction.StopAndDiscard -> dialog = RowDialog.Discard(applies)
      RowAction.RemoveAndTrash, RowAction.RemoveAndDelete -> {
        dialog = RowDialog.Remove(applies, withFiles = true)
      }
      RowAction.Remove -> state.remove(applies.map { it.task })
      RowAction.CopyLink -> commands.copy(CopiedText.Link) { applies.map { it.request.url } }
      RowAction.CopyPath -> commands.copy(CopiedText.Path) { applies.mapNotNull { it.outputFile } }
      else -> applies.forEach { commands.run(action, it) }
    }
  }

  /**
   * Runs [block] on the task of each of [rows] at once and posts one message: [done] for the
   * count that worked, why the other [targets] were left alone, and the failures with Try
   * again.
   */
  private fun launchBatch(
    rows: List<TaskRow>,
    command: TaskCommand,
    done: (Int) -> UiText,
    targets: List<TaskRow> = rows,
    block: suspend DownloadTask.() -> Unit,
  ): Job = state.launchCommand {
    val results = supervisorScope {
      rows.map { row ->
        async { row to catchingUnlessCancelled { row.task.block() }.exceptionOrNull() }
      }.awaitAll()
    }
    val failed = results.mapNotNull { (row, error) -> error?.let { row to it } }
    failed.forEach { (row, e) ->
      log.w { "Couldn't ${command.key} taskId=${row.key.taskId}: ${e.describeCauses()}" }
    }
    val retry = MessageAction(Res.string.action_try_again.text()) {
      launchBatch(failed.map { it.first }, command, done, block = block)
    }
    val worked = rows.size - failed.size
    if (worked == 0) {
      val (row, e) = failed.firstOrNull() ?: return@launchCommand
      val what = if (failed.size == 1) verbatim(row.name) else downloadsText(failed.size)
      state.messages.post(
        level = MessageLevel.Error,
        title = command.failure(what, row.device.name),
        detail = e.message?.let(::verbatim),
        taskKey = row.key.takeIf { failed.size == 1 },
        deviceId = row.key.deviceId,
        actions = listOf(retry),
        cause = e,
      )
      return@launchCommand
    }
    val skipped = skipNote(command, targets.filter { it !in rows })
    val title = listOfNotNull(
      done(worked),
      skipped,
      Res.plurals.downloads_batch_failed.text(failed.size).takeIf { failed.isNotEmpty() },
    ).joinText()
    state.messages.postFeedback(
      level = if (failed.isEmpty()) MessageLevel.Success else MessageLevel.Warning,
      title = title,
      detail = failed.firstOrNull()?.second?.message?.let(::verbatim),
      actions = if (failed.isEmpty()) emptyList() else listOf(retry),
      cause = failed.firstOrNull()?.second,
    )
  }

  // Each finished file goes to the Trash before its task is removed, so a move the system refuses
  // leaves the row to try again from; the device that wrote a partial file deletes it, since only
  // it knows where the file is.
  private fun removeToTrash(rows: List<TaskRow>) {
    val files = files ?: return
    val keys = rows.mapTo(mutableSetOf()) { it.key }
    state.selectedKeys = state.selectedKeys - keys
    if (state.inspectedTask in keys) state.inspect(null)
    val op = state.pendingOps.register(
      undoTitle = Res.string.feedback_undo_remove.text(),
      hides = keys,
      commit = {
        val results = supervisorScope {
          rows.map { row -> async { trashAndRemove(row, files) } }.awaitAll()
        }
        reportTrash(results)
      },
    )
    val single = rows.singleOrNull()
    val what = if (single != null) verbatim(single.name) else downloadsText(rows.size)
    state.messages.postFeedback(
      level = MessageLevel.Success,
      title = Res.string.downloads_trash_removed.text(what),
      detail = if (single != null) {
        Res.string.downloads_trash_its_file.text()
      } else {
        Res.string.downloads_trash_their_files.text()
      },
      actions = listOf(
        MessageAction(Res.string.action_undo.text()) { state.pendingOps.undo(op.id) }
      ),
    )
  }

  private suspend fun trashAndRemove(row: TaskRow, files: FileActions): TrashResult {
    val file = row.outputFile
    // A file that is already gone has nothing to move; its task is still removed.
    val trashing = file != null && catchingUnlessCancelled { files.exists(file) }.getOrDefault(true)
    if (file != null && trashing) {
      val refused = catchingUnlessCancelled { files.moveToTrash(file) }.exceptionOrNull()
      if (refused != null) return TrashResult(row, trashed = false, trashError = refused)
    }
    val removeError = catchingUnlessCancelled {
      row.task.remove(deleteFiles = row.state !is DownloadState.Completed)
    }.exceptionOrNull()
    return TrashResult(row, trashed = trashing, removeError = removeError)
  }

  private fun reportTrash(results: List<TrashResult>) {
    results.forEach { result ->
      val taskId = result.row.key.taskId
      result.trashError?.let {
        log.w { "Couldn't move the file of taskId=$taskId to the Trash: ${it.describeCauses()}" }
      }
      result.removeError?.let { log.w { "Couldn't remove taskId=$taskId: ${it.describeCauses()}" } }
    }
    val moved = results.count { it.trashed }
    if (moved > 0) {
      state.messages.postFeedback(
        level = MessageLevel.Success,
        title = Res.plurals.downloads_trash_moved.text(moved),
      )
    }
    val refused = results.filter { it.trashError != null }
    refused.firstOrNull()?.let { first ->
      val one = refused.size == 1
      state.messages.post(
        level = MessageLevel.Error,
        title = if (one) {
          Res.string.downloads_trash_failed.text(verbatim(first.row.name))
        } else {
          Res.plurals.downloads_trash_failed_many.text(refused.size)
        },
        detail = if (one) {
          Res.string.downloads_trash_stays.text()
        } else {
          Res.string.downloads_trash_stay.text()
        },
        deviceId = first.row.key.deviceId,
        cause = first.trashError,
      )
    }
    val unremoved = results.filter { it.removeError != null }
    unremoved.firstOrNull()?.let { first ->
      val what = if (unremoved.size == 1) {
        verbatim(first.row.name)
      } else {
        downloadsText(unremoved.size)
      }
      state.messages.post(
        level = MessageLevel.Error,
        title = Res.string.downloads_remove_failed.text(what),
        detail = first.removeError?.message?.let(::verbatim),
        deviceId = first.row.key.deviceId,
        cause = first.removeError,
      )
    }
  }

  private companion object {
    /** Order of the actions a selection offers. */
    val BATCH_ORDER: List<RowAction> = listOf(
      RowAction.Pause,
      RowAction.Resume,
      RowAction.StartNow,
      RowAction.Retry,
      RowAction.Open,
      RowAction.ShowInFolder,
      RowAction.SpeedLimit,
      RowAction.Connections,
      RowAction.Priority,
      RowAction.StartLater,
      RowAction.SendTo,
      RowAction.CopyLink,
      RowAction.CopyPath,
      RowAction.DownloadAgain,
      RowAction.StopAndDiscard,
      RowAction.Remove,
      RowAction.RemoveAndTrash,
      RowAction.RemoveAndDelete,
    )
  }
}

/** [RowActionRunner] of the app shown around this composition. */
@Composable
internal fun rememberRowActionRunner(): RowActionRunner {
  val commands = rememberRowCommands(LocalAppState.current)
  return remember(commands) { RowActionRunner(commands) }
}

/** [RowCommands] of [state], with this platform's files, clipboard and browser. */
@Composable
internal fun rememberRowCommands(state: AppState): RowCommands {
  val files = rememberFileActions()
  val clipboard = rememberSystemClipboard()
  val uriHandler = LocalUriHandler.current
  val scope = rememberCoroutineScope()
  return remember(state, files, clipboard, uriHandler, scope) {
    RowCommands(state, files, clipboard, scope, uriHandler::openUri)
  }
}

/**
 * How removing [row] with its file went: whether its file reached the Trash, the error that kept
 * it out (the task is then kept), or the error that kept the task from being removed.
 */
private class TrashResult(
  val row: TaskRow,
  val trashed: Boolean,
  val trashError: Throwable? = null,
  val removeError: Throwable? = null,
)

/** "1 download" or "3 downloads", as messages name a number of downloads. */
internal fun downloadsText(count: Int): UiText = Res.plurals.count_downloads.text(count)

/** The text of a batch [plural] for the number of rows it worked on: "Paused 2 downloads". */
private fun counted(plural: PluralStringResource): (Int) -> UiText = { n -> plural.text(n) }

/**
 * Why a batch [command] left [skipped] rows alone, such as "1 already finished" or "2 already
 * paused"; `null` when it left none.
 */
internal fun skipNote(command: TaskCommand, skipped: List<TaskRow>): UiText? {
  if (skipped.isEmpty()) return null
  val reasons = skipped.groupingBy { row ->
    when (row.state) {
      is DownloadState.Completed -> Res.plurals.downloads_skipped_finished
      is DownloadState.Downloading, DownloadState.Queued -> Res.plurals.downloads_skipped_running
      is DownloadState.Paused -> if (row.state.waitsInQueue) {
        Res.plurals.downloads_skipped_running
      } else if (command == TaskCommand.Pause) {
        Res.plurals.downloads_skipped_already_paused
      } else {
        Res.plurals.downloads_skipped_paused
      }
      is DownloadState.Scheduled -> Res.plurals.downloads_skipped_scheduled
      is DownloadState.Failed -> Res.plurals.downloads_skipped_failed
      DownloadState.Canceled -> Res.plurals.downloads_skipped_canceled
    }
  }.eachCount()
  return reasons.entries.map { (reason, count) -> reason.text(count) }.joinText()
}
