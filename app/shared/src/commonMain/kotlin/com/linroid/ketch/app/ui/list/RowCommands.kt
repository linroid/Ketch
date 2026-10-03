package com.linroid.ketch.app.ui.list

import com.linroid.ketch.api.Destination
import com.linroid.ketch.api.DownloadPriority
import com.linroid.ketch.api.DownloadSchedule
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.api.isName
import com.linroid.ketch.app.feedback.MessageLevel
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.load
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
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
import com.linroid.ketch.app.state.taskActions
import com.linroid.ketch.app.util.errorDetails
import com.linroid.ketch.app.util.referer
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.downloads_copied_details
import ketch.app.shared.generated.resources.downloads_copied_error
import ketch.app.shared.generated.resources.downloads_copied_link
import ketch.app.shared.generated.resources.downloads_copied_links
import ketch.app.shared.generated.resources.downloads_copied_path
import ketch.app.shared.generated.resources.downloads_copied_paths
import ketch.app.shared.generated.resources.downloads_copy_failed_details
import ketch.app.shared.generated.resources.downloads_copy_failed_error
import ketch.app.shared.generated.resources.downloads_copy_failed_link
import ketch.app.shared.generated.resources.downloads_copy_failed_links
import ketch.app.shared.generated.resources.downloads_copy_failed_path
import ketch.app.shared.generated.resources.downloads_copy_failed_paths
import ketch.app.shared.generated.resources.downloads_failed_connections
import ketch.app.shared.generated.resources.downloads_failed_open
import ketch.app.shared.generated.resources.downloads_failed_pause
import ketch.app.shared.generated.resources.downloads_failed_priority
import ketch.app.shared.generated.resources.downloads_failed_reconnect
import ketch.app.shared.generated.resources.downloads_failed_reschedule
import ketch.app.shared.generated.resources.downloads_failed_resume
import ketch.app.shared.generated.resources.downloads_failed_retry
import ketch.app.shared.generated.resources.downloads_failed_reveal
import ketch.app.shared.generated.resources.downloads_failed_speed_limit
import ketch.app.shared.generated.resources.downloads_open_source_failed
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.PluralStringResource
import org.jetbrains.compose.resources.StringResource

/**
 * A command the Downloads list runs on tasks, as [AppState.pending] holds it and as a failure
 * names it.
 *
 * @property key what [AppState.pending] holds a task with while the command runs, and the
 *   command in logs; never shown.
 */
internal enum class TaskCommand(val key: String, private val failure: StringResource) {
  Pause("pause", Res.string.downloads_failed_pause),
  Resume("resume", Res.string.downloads_failed_resume),
  Retry("retry", Res.string.downloads_failed_retry),
  Reconnect("reconnect", Res.string.downloads_failed_reconnect),
  Open("open", Res.string.downloads_failed_open),
  Reveal("reveal", Res.string.downloads_failed_reveal),
  SpeedLimit("speed-limit", Res.string.downloads_failed_speed_limit),
  Priority("priority", Res.string.downloads_failed_priority),
  Connections("connections", Res.string.downloads_failed_connections),
  Reschedule("reschedule", Res.string.downloads_failed_reschedule);

  /**
   * The failure of this command on [what], a task's name or a number of downloads, on [device]:
   * "Couldn't pause ubuntu.iso on NAS-Basement".
   */
  fun failure(what: UiText, device: UiText): UiText = failure.text(what, device)
}

/** What the Downloads list copies to the clipboard, as its confirmations and failures name it. */
internal enum class CopiedText(
  private val one: StringResource,
  private val many: PluralStringResource?,
  private val failedOne: StringResource,
  private val failedMany: StringResource?,
) {
  Link(
    Res.string.downloads_copied_link,
    Res.plurals.downloads_copied_links,
    Res.string.downloads_copy_failed_link,
    Res.string.downloads_copy_failed_links,
  ),
  Path(
    Res.string.downloads_copied_path,
    Res.plurals.downloads_copied_paths,
    Res.string.downloads_copy_failed_path,
    Res.string.downloads_copy_failed_paths,
  ),
  Error(Res.string.downloads_copied_error, null, Res.string.downloads_copy_failed_error, null),
  Details(
    Res.string.downloads_copied_details,
    null,
    Res.string.downloads_copy_failed_details,
    null,
  );

  /** "Copied link", or "Copied 3 links" for [count] of them. */
  fun copied(count: Int = 1): UiText = many?.takeIf { count > 1 }?.text(count) ?: one.text()

  /** "Couldn't copy the link", or "Couldn't copy the links" for several. */
  fun failed(count: Int = 1): UiText = failedMany?.takeIf { count > 1 }?.text() ?: failedOne.text()
}

/**
 * Runs the [RowAction]s of the Downloads list on their tasks.
 *
 * Task commands go through [AppState.runTaskCommand], so a failure shows one toast naming the
 * device and never cancels another command. Removing a task and discarding its progress go
 * through [AppState]'s Undo.
 *
 * @param files opens and reveals downloaded files; `null` where they are out of reach.
 * @param clipboard receives copied links, paths and error details; `null` where there is none.
 * @param scope runs clipboard writes.
 * @param openUri opens a web page, such as the page a link was captured from.
 */
internal class RowCommands(
  private val state: AppState,
  private val files: FileActions?,
  private val clipboard: SystemClipboard?,
  private val scope: CoroutineScope,
  private val openUri: (String) -> Unit,
) {
  /** The actions [row] offers that can run here, in menu order, its primary one included. */
  fun menu(row: TaskRow): List<RowAction> =
    taskActions(row.request, row.state, row.device, stalled = row.isStalled).menu
      .filter { canRun(it, row) }

  /**
   * The row's trailing button: its primary action, or, when that cannot run here, the first
   * other fix of its failure that can.
   */
  fun trailing(row: TaskRow): RowAction? {
    val primary = row.content.primary ?: return null
    if (canRun(primary, row)) return primary
    return row.content.error?.secondary?.firstOrNull { canRun(it, row) }
  }

  /** Whether [action] can run on [row] here; files need [files] and copies a clipboard. */
  fun canRun(action: RowAction, row: TaskRow): Boolean = when (action) {
    RowAction.Open -> files != null && outputPath(row) != null
    RowAction.ShowInFolder -> files?.revealLabel != null && folderPath(row) != null
    RowAction.CopyPath -> clipboard != null && outputPath(row) != null
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
    when (action) {
      RowAction.Pause -> command(row, TaskCommand.Pause) { pause() }
      RowAction.Resume -> command(row, TaskCommand.Resume) { resume() }
      RowAction.Retry -> command(row, TaskCommand.Retry) { resume() }
      RowAction.Reconnect -> command(row, TaskCommand.Reconnect) {
        pause()
        resume()
      }
      is RowAction.RetryWithConnections -> command(row, TaskCommand.Retry) {
        setConnections(action.connections)
        resume()
      }
      RowAction.StartNow -> state.startNow(task)
      RowAction.DownloadAgain -> state.redownload(task)
      RowAction.Open -> {
        val path = outputPath(row) ?: return
        val files = files ?: return
        command(row, TaskCommand.Open) { files.open(path) }
      }
      RowAction.ShowInFolder -> {
        val path = folderPath(row) ?: return
        val files = files ?: return
        command(row, TaskCommand.Reveal) { files.reveal(path) }
      }
      RowAction.CopyLink -> copy(CopiedText.Link) { row.request.url }
      RowAction.CopyPath -> outputPath(row)?.let { path -> copy(CopiedText.Path) { path } }
      RowAction.CopyError -> row.content.error?.let { error ->
        copy(CopiedText.Error) {
          listOfNotNull(error.title, error.hint).map { it.load() }.joinToString("\n")
        }
      }
      RowAction.CopyDetails -> (row.state as? DownloadState.Failed)?.let { failed ->
        copy(CopiedText.Details) { errorDetails(failed.error, row.request, task.taskId) }
      }
      RowAction.EditLink,
      RowAction.RetryWithOptions,
      RowAction.EnterCredentials -> state.openIntake(retryRequest(row))
      RowAction.FindAnotherSource -> state.openDiscover(DiscoverRequest(query = row.name))
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
    command(row, TaskCommand.SpeedLimit) { setSpeedLimit(limit) }

  /** Gives [row]'s task [priority]. */
  fun setPriority(row: TaskRow, priority: DownloadPriority): Job =
    command(row, TaskCommand.Priority) { setPriority(priority) }

  /** Lets [row]'s task open [connections] connections, or peers for a torrent. */
  fun setConnections(row: TaskRow, connections: Int): Job =
    command(row, TaskCommand.Connections) { setConnections(connections) }

  /** Starts [row]'s task at [schedule]. */
  fun reschedule(row: TaskRow, schedule: DownloadSchedule): Job =
    command(row, TaskCommand.Reschedule) { reschedule(schedule) }

  /** Removes [row]'s task, and its files with [deleteFiles], once the Undo window ends. */
  fun remove(row: TaskRow, deleteFiles: Boolean) {
    state.remove(listOf(row.task), deleteFiles)
  }

  private fun command(
    row: TaskRow,
    command: TaskCommand,
    block: suspend DownloadTask.() -> Unit,
  ): Job = state.runTaskCommand(
    task = row.task,
    pendingKey = command.key,
    failure = { device -> command.failure(verbatim(row.name), device) },
    block = block,
  )

  private fun copy(what: CopiedText, text: suspend () -> String) {
    val clipboard = clipboard ?: return
    scope.launch {
      catchingUnlessCancelled { clipboard.writeText(text()) }
        .onSuccess { state.messages.post(MessageLevel.Success, what.copied()) }
        .onFailure { e -> state.messages.post(MessageLevel.Error, what.failed(), cause = e) }
    }
  }

  private fun openPage(url: String) {
    // A link the platform cannot parse, or a device without a browser, throws.
    runCatching { openUri(url) }.onFailure { e ->
      state.messages.post(
        MessageLevel.Error,
        Res.string.downloads_open_source_failed.text(),
        cause = e,
      )
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
    /** Pending key of [setSpeedLimit], which marks the speed limit control pending. */
    fun speedLimitLabel(row: TaskRow): String = TaskCommand.SpeedLimit.key

    /** Pending key of [setPriority]. */
    fun priorityLabel(row: TaskRow): String = TaskCommand.Priority.key

    /** Pending key of [setConnections]. */
    fun connectionsLabel(row: TaskRow): String = TaskCommand.Connections.key

    /** Pending key of [reschedule]. */
    fun rescheduleLabel(row: TaskRow): String = TaskCommand.Reschedule.key

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

/** Path of the file a completed row saved. */
private fun outputPath(row: TaskRow): String? =
  (row.state as? DownloadState.Completed)?.outputPath?.ifBlank { null }

/** The web page [row]'s link was captured from; a `Referer` of another scheme is not opened. */
private fun sourcePage(row: TaskRow): String? = row.request.referer?.takeIf { referer ->
  WEB_SCHEMES.any { referer.startsWith(it, ignoreCase = true) }
}

private val WEB_SCHEMES = listOf("https://", "http://")

/** Where [row]'s file is or goes: its output, else a destination that names a path. */
private fun folderPath(row: TaskRow): String? =
  outputPath(row) ?: row.request.destination?.takeUnless(Destination::isName)?.value
