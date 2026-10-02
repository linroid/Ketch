package com.linroid.ketch.app.ui.list

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.isName
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.platform.FileActions
import com.linroid.ketch.app.platform.SystemClipboard
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DiscoverRequest
import com.linroid.ketch.app.state.IntakeRequest
import com.linroid.ketch.app.state.IntakeSeed
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.util.errorDetails
import com.linroid.ketch.app.util.referer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Runs the [RowAction]s of the Downloads list on their tasks.
 *
 * Task commands go through [AppState.runTaskCommand], so a failure shows one toast naming the
 * device and never cancels another command. Removing a task and discarding its progress go
 * through [AppState]'s Undo.
 *
 * @property files opens and reveals downloaded files; `null` where they are out of reach.
 * @property clipboard receives copied links, paths and error details; `null` where there is none.
 * @property scope runs clipboard writes.
 * @param openUri opens a web page, such as the page a link was captured from.
 */
internal class RowCommands(
  val state: AppState,
  val files: FileActions?,
  val clipboard: SystemClipboard?,
  val scope: CoroutineScope,
  private val openUri: (String) -> Unit,
) {
  /** Whether [action] can run on [row] here; files need [files] and copies a clipboard. */
  fun canRun(action: RowAction, row: TaskRow): Boolean = when (action) {
    RowAction.Open -> files != null && row.outputFile != null
    RowAction.ShowInFolder -> files?.revealLabel != null && folderPath(row) != null
    RowAction.CopyPath -> clipboard != null && row.outputFile != null
    RowAction.CopyLink, RowAction.CopyError, RowAction.CopyDetails -> clipboard != null
    RowAction.OpenSourcePage -> sourcePage(row) != null
    RowAction.FindAnotherSource -> row.device.capabilities.canDiscover
    else -> true
  }

  /**
   * Runs [action] on [row]. Actions that open a panel, a menu or a dialog, such as
   * [RowAction.SpeedLimit] or [RowAction.RemoveAndDelete], belong to the caller and do nothing
   * here. [RowAction.StopAndDiscard] discards at once, so the caller confirms it first.
   */
  fun run(action: RowAction, row: TaskRow) {
    val task = row.task
    val name = row.name
    when (action) {
      RowAction.Pause -> state.runTaskCommand(task, "pause $name") { pause() }
      RowAction.Resume -> state.runTaskCommand(task, "resume $name") { resume() }
      RowAction.Retry -> state.runTaskCommand(task, "retry $name") { resume() }
      RowAction.Reconnect -> state.runTaskCommand(task, "reconnect $name") {
        pause()
        resume()
      }
      is RowAction.RetryWithConnections -> state.runTaskCommand(task, "retry $name") {
        setConnections(action.connections)
        resume()
      }
      RowAction.StartNow -> state.startNow(task)
      RowAction.DownloadAgain -> state.redownload(task)
      RowAction.Open -> {
        val path = row.outputFile ?: return
        val files = files ?: return
        state.runTaskCommand(task, "open $name") { files.open(path) }
      }
      RowAction.ShowInFolder -> {
        val path = folderPath(row) ?: return
        val files = files ?: return
        state.runTaskCommand(task, "show $name in its folder") { files.reveal(path) }
      }
      RowAction.CopyLink -> copy(listOf(row.request.url), "link")
      RowAction.CopyPath -> row.outputFile?.let { copy(listOf(it), "file path") }
      RowAction.CopyError -> row.content.error?.let { error ->
        copy(listOf(listOfNotNull(error.title, error.hint).joinToString("\n")), "error")
      }
      RowAction.CopyDetails -> (row.state as? DownloadState.Failed)?.let { failed ->
        copy(listOf(errorDetails(failed.error, row.request, task.taskId)), "details")
      }
      RowAction.EditLink,
      RowAction.RetryWithOptions,
      RowAction.EnterCredentials -> state.openIntake(retryRequest(row))
      RowAction.FindAnotherSource -> state.openDiscover(DiscoverRequest(query = name))
      RowAction.OpenSourcePage -> sourcePage(row)?.let(::openPage)
      RowAction.Remove -> state.remove(listOf(task))
      RowAction.StopAndDiscard -> state.cancel(listOf(task))
      RowAction.SpeedLimit,
      RowAction.Connections,
      RowAction.Priority,
      RowAction.StartLater,
      RowAction.SendTo,
      RowAction.Details,
      RowAction.RemoveAndTrash,
      RowAction.RemoveAndDelete -> Unit
    }
  }

  /** Caps [row]'s task at [limit]. */
  fun setSpeedLimit(row: TaskRow, limit: SpeedLimit): Job =
    state.runTaskCommand(row.task, speedLimitLabel(row)) { setSpeedLimit(limit) }

  /** Gives [row]'s task [priority]. */
  fun setPriority(row: TaskRow, priority: DownloadPriority): Job =
    state.runTaskCommand(row.task, priorityLabel(row)) { setPriority(priority) }

  /** Lets [row]'s task open [connections] connections, or peers for a torrent. */
  fun setConnections(row: TaskRow, connections: Int): Job =
    state.runTaskCommand(row.task, connectionsLabel(row)) { setConnections(connections) }

  /** Starts [row]'s task at [schedule]. */
  fun reschedule(row: TaskRow, schedule: DownloadSchedule): Job =
    state.runTaskCommand(row.task, rescheduleLabel(row)) { reschedule(schedule) }

  /** Copies [lines], one per line, and confirms it as "Copied link" or "Copied 3 links". */
  fun copy(lines: List<String>, what: String) {
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

  private fun openPage(url: String) {
    // A link the platform cannot parse, or a device without a browser, throws.
    runCatching { openUri(url) }.onFailure { e ->
      state.messages.post(MessageLevel.Error, "Couldn't open the source page", cause = e)
    }
  }

  /** Reopens the add sheet with [row]'s link and options, to retry it with changes. */
  private fun retryRequest(row: TaskRow): IntakeRequest {
    val request = row.request
    val seed = IntakeSeed(
      url = request.url,
      fileName = request.destination?.takeIf { it.isName() }?.value,
      headers = request.headers,
      properties = request.properties,
    )
    return IntakeRequest(
      seeds = listOf(seed),
      targetDeviceId = row.key.deviceId,
      retryOf = row.key,
    )
  }

  companion object {
    /** Command label of [setSpeedLimit], which marks the speed limit control pending. */
    fun speedLimitLabel(row: TaskRow): String = "set the speed limit of ${row.name}"

    /** Command label of [setPriority]. */
    fun priorityLabel(row: TaskRow): String = "set the priority of ${row.name}"

    /** Command label of [setConnections]. */
    fun connectionsLabel(row: TaskRow): String = "set the connections of ${row.name}"

    /** Command label of [reschedule]. */
    fun rescheduleLabel(row: TaskRow): String = "reschedule ${row.name}"

    /**
     * Whether a command in [pending] runs on [row]'s task, other than a change of its settings,
     * which its own control shows.
     */
    fun isBusy(row: TaskRow, pending: Set<Pair<TaskKey, String>>): Boolean {
      val settings = setOf(
        speedLimitLabel(row),
        priorityLabel(row),
        connectionsLabel(row),
        rescheduleLabel(row),
      )
      return pending.any { (key, label) -> key == row.key && label !in settings }
    }
  }
}

/** Path of the file a completed row saved, or `null`. */
internal val TaskRow.outputFile: String?
  get() = (state as? DownloadState.Completed)?.outputPath?.ifBlank { null }

/** The web page [row]'s link was captured from; a `Referer` of another scheme is not opened. */
private fun sourcePage(row: TaskRow): String? = row.request.referer?.takeIf { referer ->
  WEB_SCHEMES.any { referer.startsWith(it, ignoreCase = true) }
}

private val WEB_SCHEMES = listOf("https://", "http://")

/** Where [row]'s file is or goes: its output, else a destination that names a path. */
private fun folderPath(row: TaskRow): String? =
  row.outputFile ?: row.request.destination?.takeUnless(Destination::isName)?.value
