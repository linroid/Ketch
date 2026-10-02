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
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.platform.FileActions
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.platform.rememberFileActions
import com.linroid.ketch.app.platform.rememberSystemClipboard
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.state.taskActions
import com.linroid.ketch.app.ui.dialog.RemovalPlan
import com.linroid.ketch.app.ui.list.RowCommands
import com.linroid.ketch.app.util.priorityLabel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.supervisorScope

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
internal data class BatchAction(val action: RowAction, val rows: List<TaskRow>) {
  /** How many rows it applies to. */
  val count: Int get() = rows.size
}

/**
 * Runs the [RowAction]s of the Downloads list on one row or on every selected row, and holds the
 * dialogs they ask for, which [RowActionDialogs] shows.
 *
 * One row runs through [commands], as its trailing button does. Several rows run as one batch
 * whose outcome is a single message, such as "Paused 2 · 1 already finished", and whose failures
 * offer Try again for the rows that failed. Removing, clearing and discarding progress go through
 * the Undo of [AppState].
 *
 * @param files opens, reveals and trashes downloaded files; `null` where they are out of reach.
 * @param clipboard receives copied links and paths; `null` where there is none.
 * @param scope runs clipboard writes and file checks. Batches run in the app's scope instead,
 *   so leaving the screen never stops one with half the rows done, and Try again still works.
 */
@Stable
internal class RowActionRunner(
  val state: AppState,
  val commands: RowCommands,
  val files: FileActions?,
  private val clipboard: SystemClipboard?,
  private val scope: CoroutineScope,
) {
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
    val path = (row.state as? DownloadState.Completed)?.outputPath?.ifBlank { null } ?: return
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
   * checking each file again first; see [AppState.clearMissing].
   */
  fun clearMissing() {
    files?.let { state.clearMissing(it) }
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
  fun setSpeedLimit(rows: List<TaskRow>, limit: SpeedLimit) {
    val row = rows.singleOrNull()
    if (row != null) {
      commands.setSpeedLimit(row, limit)
      return
    }
    val title = if (limit.isUnlimited) {
      { n: Int -> "Removed the speed limit of ${downloads(n)}" }
    } else {
      { n: Int -> "Limited ${downloads(n)} to ${formatSpeedLimit(limit)}" }
    }
    launchBatch(rows, "set the speed limit of", title) { setSpeedLimit(limit) }
  }

  /** Splits [total] evenly between [rows], each capped at its share. */
  fun shareSpeed(rows: List<TaskRow>, total: SpeedLimit) {
    if (rows.isEmpty() || total.isUnlimited) return
    val share = SpeedLimit.of((total.bytesPerSecond / rows.size).coerceAtLeast(1))
    launchBatch(rows, "set the speed limit of", { n ->
      "Sharing ${formatSpeedLimit(total)} across ${downloads(n)}"
    }) { setSpeedLimit(share) }
  }

  /** Gives each of [rows] [priority]. */
  fun setPriority(rows: List<TaskRow>, priority: DownloadPriority) {
    val row = rows.singleOrNull()
    if (row != null) {
      commands.setPriority(row, priority)
      return
    }
    launchBatch(rows, "set the priority of", { n ->
      "Set ${downloads(n)} to ${priorityLabel(priority)} priority"
    }) { setPriority(priority) }
  }

  /** Lets each of [rows] open [connections] connections, or peers for a torrent. */
  fun setConnections(rows: List<TaskRow>, connections: Int) {
    val row = rows.singleOrNull()
    if (row != null) {
      commands.setConnections(row, connections)
      return
    }
    launchBatch(rows, "set the connections of", { n ->
      "Set ${downloads(n)} to $connections connections"
    }) { setConnections(connections) }
  }

  /** Starts each of [rows] at [schedule]. */
  fun reschedule(rows: List<TaskRow>, schedule: DownloadSchedule) {
    val row = rows.singleOrNull()
    if (row != null) {
      commands.reschedule(row, schedule)
      return
    }
    launchBatch(rows, "reschedule", { n -> "Rescheduled ${downloads(n)}" }) {
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
      RowAction.Pause -> launchBatch(applies, "pause", { n -> "Paused ${downloads(n)}" }, rows) {
        pause()
      }
      RowAction.Resume -> launchBatch(applies, "resume", { "Resumed ${downloads(it)}" }, rows) {
        resume()
      }
      RowAction.Retry -> {
        val app = state
        launchBatch(applies, "retry", { n -> "Retrying ${downloads(n)}" }, rows) {
          app.retryInBatch(this)
        }
      }
      RowAction.StartNow -> state.startNow(applies.map { it.task })
      RowAction.DownloadAgain -> state.redownload(applies.map { it.task })
      RowAction.StopAndDiscard -> dialog = RowDialog.Discard(applies)
      RowAction.RemoveAndTrash, RowAction.RemoveAndDelete -> {
        dialog = RowDialog.Remove(applies, withFiles = true)
      }
      RowAction.Remove -> state.remove(applies.map { it.task })
      RowAction.CopyLink -> copy(applies.map { it.request.url }, "link")
      RowAction.CopyPath -> copy(applies.mapNotNull { it.outputFile }, "file path")
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
    command: String,
    done: (Int) -> String,
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
      log.w { "Couldn't $command taskId=${row.key.taskId}: ${e.describeCauses()}" }
    }
    val retry = MessageAction("Try again") {
      launchBatch(failed.map { it.first }, command, done, block = block)
    }
    val worked = rows.size - failed.size
    if (worked == 0) {
      val (row, e) = failed.firstOrNull() ?: return@launchCommand
      val what = if (failed.size == 1) row.name else downloads(failed.size)
      state.messages.post(
        level = MessageLevel.Error,
        title = "Couldn't $command $what on ${row.device.name}",
        detail = e.message,
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
      "${failed.size} failed".takeIf { failed.isNotEmpty() },
    ).joinToString(" · ")
    state.messages.post(
      level = if (failed.isEmpty()) MessageLevel.Success else MessageLevel.Warning,
      title = title,
      detail = failed.firstOrNull()?.second?.message,
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
      label = "Remove",
      hides = keys,
      commit = {
        val results = supervisorScope {
          rows.map { row -> async { trashAndRemove(row, files) } }.awaitAll()
        }
        reportTrash(results)
      },
    )
    val title = if (rows.size == 1) "Removed ${rows.single().name}" else {
      "Removed ${downloads(rows.size)}"
    }
    state.messages.post(
      level = MessageLevel.Success,
      title = title,
      detail = if (rows.size == 1) "Its file goes to the Trash" else "Their files go to the Trash",
      actions = listOf(MessageAction("Undo") { state.pendingOps.undo(op.id) }),
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
      val what = if (moved == 1) "1 file" else "$moved files"
      state.messages.post(MessageLevel.Success, "Moved $what to the Trash")
    }
    val refused = results.filter { it.trashError != null }
    refused.firstOrNull()?.let { first ->
      state.messages.post(
        level = MessageLevel.Error,
        title = if (refused.size == 1) {
          "Couldn't move ${first.row.name} to the Trash"
        } else {
          "Couldn't move ${refused.size} files to the Trash"
        },
        detail = if (refused.size == 1) "The download stays in the list" else {
          "The downloads stay in the list"
        },
        deviceId = first.row.key.deviceId,
        cause = first.trashError,
      )
    }
    val unremoved = results.filter { it.removeError != null }
    unremoved.firstOrNull()?.let { first ->
      state.messages.post(
        level = MessageLevel.Error,
        title = if (unremoved.size == 1) {
          "Couldn't remove ${first.row.name}"
        } else {
          "Couldn't remove ${downloads(unremoved.size)}"
        },
        detail = first.removeError?.message,
        deviceId = first.row.key.deviceId,
        cause = first.removeError,
      )
    }
  }

  private fun copy(lines: List<String>, what: String) {
    val clipboard = clipboard ?: return
    if (lines.isEmpty()) return
    scope.launch {
      catchingUnlessCancelled { clipboard.writeText(lines.joinToString("\n")) }
        .onSuccess {
          val title = if (lines.size == 1) "Copied $what" else "Copied ${lines.size} ${what}s"
          state.messages.post(MessageLevel.Success, title)
        }
        .onFailure { e ->
          val noun = if (lines.size == 1) what else "${what}s"
          state.messages.post(MessageLevel.Error, "Couldn't copy the $noun", cause = e)
        }
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
  val state = LocalAppState.current
  val files = rememberFileActions()
  val clipboard = rememberSystemClipboard()
  val uriHandler = LocalUriHandler.current
  val scope = rememberCoroutineScope()
  return remember(state, files, clipboard, uriHandler, scope) {
    val commands = RowCommands(state, files, clipboard, scope, uriHandler::openUri)
    RowActionRunner(state, commands, files, clipboard, scope)
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

/** Path of the file a completed row saved, or `null`. */
internal val TaskRow.outputFile: String?
  get() = (state as? DownloadState.Completed)?.outputPath?.ifBlank { null }

/** "1 download" or "3 downloads". */
internal fun downloads(count: Int): String = if (count == 1) "1 download" else "$count downloads"

/**
 * Why a batch [command] left [skipped] rows alone, such as "1 already finished" or "2 already
 * paused"; `null` when it left none.
 */
internal fun skipNote(command: String, skipped: List<TaskRow>): String? {
  if (skipped.isEmpty()) return null
  val reasons = skipped.groupingBy { row ->
    when (row.state) {
      is DownloadState.Completed -> "already finished"
      is DownloadState.Downloading, DownloadState.Queued -> "already running"
      is DownloadState.Paused -> if (command == "pause") "already paused" else "paused"
      is DownloadState.Scheduled -> "scheduled"
      is DownloadState.Failed -> "with an error"
      DownloadState.Canceled -> "canceled"
    }
  }.eachCount()
  return reasons.entries.joinToString(" · ") { (reason, count) -> "$count $reason" }
}
