package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.util.toCopy
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_row_connections
import ketch.app.shared.generated.resources.action_row_copy_details
import ketch.app.shared.generated.resources.action_row_copy_error
import ketch.app.shared.generated.resources.action_row_copy_link
import ketch.app.shared.generated.resources.action_row_copy_path
import ketch.app.shared.generated.resources.action_row_details
import ketch.app.shared.generated.resources.action_row_download_again
import ketch.app.shared.generated.resources.action_row_edit_link
import ketch.app.shared.generated.resources.action_row_enter_credentials
import ketch.app.shared.generated.resources.action_row_find_another_source
import ketch.app.shared.generated.resources.action_row_open
import ketch.app.shared.generated.resources.action_row_open_source_page
import ketch.app.shared.generated.resources.action_row_pause
import ketch.app.shared.generated.resources.action_row_priority
import ketch.app.shared.generated.resources.action_row_reconnect
import ketch.app.shared.generated.resources.action_row_remove
import ketch.app.shared.generated.resources.action_row_remove_and_delete
import ketch.app.shared.generated.resources.action_row_remove_and_trash
import ketch.app.shared.generated.resources.action_row_resume
import ketch.app.shared.generated.resources.action_row_retry
import ketch.app.shared.generated.resources.action_row_retry_with_connections
import ketch.app.shared.generated.resources.action_row_retry_with_options
import ketch.app.shared.generated.resources.action_row_send_to
import ketch.app.shared.generated.resources.action_row_show_in_folder
import ketch.app.shared.generated.resources.action_row_speed_limit
import ketch.app.shared.generated.resources.action_row_start_later
import ketch.app.shared.generated.resources.action_row_start_now
import ketch.app.shared.generated.resources.action_row_stop_and_discard

/**
 * Something the user can do with a task. Row buttons, hover actions, the context menu, the
 * selection bar, the command palette, shortcuts, the tray and notifications all take their
 * actions from [taskActions], so a task offers the same choices everywhere.
 *
 * @property label menu text.
 * @property destructive whether the action discards the task or its progress. Menus put these
 *   last, after a divider.
 */
sealed class RowAction(val label: UiText, val destructive: Boolean = false) {
  /** Pauses a downloading or queued task. */
  data object Pause : RowAction(Res.string.action_row_pause.text())

  /** Resumes a paused task. */
  data object Resume : RowAction(Res.string.action_row_resume.text())

  /** Pauses a stalled task and resumes it, so it opens fresh connections. */
  data object Reconnect : RowAction(Res.string.action_row_reconnect.text())

  /**
   * Starts a waiting task now: a queued task becomes urgent, which may pause another, and a
   * scheduled task is rescheduled to start immediately.
   */
  data object StartNow : RowAction(Res.string.action_row_start_now.text())

  /** Opens the speed limit choices. */
  data object SpeedLimit : RowAction(Res.string.action_row_speed_limit.text())

  /** Opens the connection count choices, or the peer limit of a torrent. */
  data object Connections : RowAction(Res.string.action_row_connections.text())

  /** Opens the priority choices. */
  data object Priority : RowAction(Res.string.action_row_priority.text())

  /** Opens the choices of when to start. */
  data object StartLater : RowAction(Res.string.action_row_start_later.text())

  /** Opens the devices to send the task to. */
  data object SendTo : RowAction(Res.string.action_row_send_to.text())

  /** Copies the task's link. */
  data object CopyLink : RowAction(Res.string.action_row_copy_link.text())

  /** Opens the task in the inspector. */
  data object Details : RowAction(Res.string.action_row_details.text())

  /** Opens the downloaded file. */
  data object Open : RowAction(Res.string.action_row_open.text())

  /** Shows the file, or the folder it belongs in, in the file manager. */
  data object ShowInFolder : RowAction(Res.string.action_row_show_in_folder.text())

  /** Copies the path of the downloaded file. */
  data object CopyPath : RowAction(Res.string.action_row_copy_path.text())

  /** Adds the task's request again from the start and removes this task. */
  data object DownloadAgain : RowAction(Res.string.action_row_download_again.text())

  /** Resumes a failed task from where it stopped. */
  data object Retry : RowAction(Res.string.action_row_retry.text())

  /** Opens the add sheet with the task's options, to retry with changes. */
  data object RetryWithOptions : RowAction(Res.string.action_row_retry_with_options.text())

  /** Opens the add sheet to change the link of a task the server turned away. */
  data object EditLink : RowAction(Res.string.action_row_edit_link.text())

  /**
   * Lowers the task to [connections] connections and resumes it.
   *
   * @property connections connection count to retry with.
   */
  data class RetryWithConnections(val connections: Int) : RowAction(
    Res.plurals.action_row_retry_with_connections.text(connections)
  )

  /** Opens the add sheet with user name and password fields. */
  data object EnterCredentials : RowAction(Res.string.action_row_enter_credentials.text())

  /** Opens Discover to look for the file elsewhere, searching for its name. */
  data object FindAnotherSource : RowAction(Res.string.action_row_find_another_source.text())

  /** Opens the page the link was captured from (its `Referer`). */
  data object OpenSourcePage : RowAction(Res.string.action_row_open_source_page.text())

  /** Copies the error's title and hint. */
  data object CopyError : RowAction(Res.string.action_row_copy_error.text())

  /** Copies technical details for a bug report. */
  data object CopyDetails : RowAction(Res.string.action_row_copy_details.text())

  /** Cancels the task after confirmation; its progress cannot be resumed. */
  data object StopAndDiscard :
    RowAction(Res.string.action_row_stop_and_discard.text(), destructive = true)

  /** Removes the task from the list and keeps its files, with Undo. */
  data object Remove : RowAction(Res.string.action_row_remove.text(), destructive = true)

  /** Removes the task and moves its files to the Trash, after confirmation. */
  data object RemoveAndTrash :
    RowAction(Res.string.action_row_remove_and_trash.text(), destructive = true)

  /** Removes the task and deletes its files, after confirmation. */
  data object RemoveAndDelete :
    RowAction(Res.string.action_row_remove_and_delete.text(), destructive = true)
}

/**
 * What the app can do with the tasks of one device.
 *
 * @property isRemote whether the device is another Ketch instance reached over the network.
 * @property canReschedule whether its tasks can be rescheduled; remote ones cannot yet.
 * @property canOpenFiles whether this app can open and reveal its downloaded files.
 * @property canTrash whether removed files can go to the Trash instead of being deleted.
 * @property canDiscover whether AI discovery runs here, to find another source for a link that
 *   no longer works; a search made before it is set up waits on its setup page.
 */
data class RowCapabilities(
  val isRemote: Boolean,
  val canReschedule: Boolean,
  val canOpenFiles: Boolean,
  val canTrash: Boolean,
  val canDiscover: Boolean = false,
) {
  companion object {
    /** The engine inside the app. */
    fun local(
      canOpenFiles: Boolean = true,
      canTrash: Boolean = false,
      canDiscover: Boolean = false,
    ): RowCapabilities = RowCapabilities(
      isRemote = false,
      canReschedule = true,
      canOpenFiles = canOpenFiles,
      canTrash = canTrash,
      canDiscover = canDiscover,
    )

    /** Another Ketch instance, whose files this app cannot reach. */
    fun remote(canDiscover: Boolean = false): RowCapabilities = RowCapabilities(
      isRemote = true,
      canReschedule = false,
      canOpenFiles = false,
      canTrash = false,
      canDiscover = canDiscover,
    )
  }
}

/**
 * The device a task runs on, as far as its copy and actions need it.
 *
 * @property name name shown to the user, such as "This Mac" or "NAS-Basement".
 * @property capabilities what the app can do with the device's tasks.
 * @property usableSpace usable space in its download directory in bytes, or `null` if unknown.
 */
data class DeviceInfo(
  val name: UiText,
  val capabilities: RowCapabilities,
  val usableSpace: Long? = null,
)

/**
 * The actions a task offers.
 *
 * @property primary the row's trailing button, or `null` when the row offers none.
 * @property hover the buttons shown while the pointer is over the row, before "⋯".
 * @property menu the context menu in order, [destructive][RowAction.destructive] items last.
 */
data class TaskActions(
  val primary: RowAction?,
  val hover: List<RowAction>,
  val menu: List<RowAction>,
)

/**
 * The actions of a task downloading [request] on [device] while in [state].
 *
 * @param retryCount how many times the engine retries a failure, to explain failed tasks.
 * @param stalled whether a downloading task has received no data for a while.
 * @param starting whether a queued task already holds a slot; see [isStarting].
 * @param fileMissing whether a completed task's file is gone; only checked on local devices.
 */
fun taskActions(
  request: DownloadRequest,
  state: DownloadState,
  device: DeviceInfo,
  retryCount: Int = 0,
  stalled: Boolean = false,
  starting: Boolean = false,
  fileMissing: Boolean = false,
): TaskActions {
  val capabilities = device.capabilities
  return when (state) {
    is DownloadState.Downloading -> {
      val primary = if (stalled) RowAction.Reconnect else RowAction.Pause
      waitingOrRunning(primary, listOf(primary, RowAction.Pause).distinct(), capabilities)
    }
    // Paused for an urgent download, it still waits in the queue: Resume would do nothing.
    is DownloadState.Paused -> if (state.waitsInQueue) {
      waitingOrRunning(
        RowAction.StartNow,
        listOf(RowAction.Pause, RowAction.StartNow),
        capabilities
      )
    } else {
      waitingOrRunning(RowAction.Resume, listOf(RowAction.Resume), capabilities)
    }
    // Out of the queue, it already started: Start now would do nothing.
    is DownloadState.Queued -> if (starting) {
      waitingOrRunning(RowAction.Pause, listOf(RowAction.Pause), capabilities)
    } else {
      waitingOrRunning(
        RowAction.StartNow,
        listOf(RowAction.Pause, RowAction.StartNow),
        capabilities
      )
    }
    is DownloadState.Scheduled -> {
      val startNow = RowAction.StartNow.takeIf { capabilities.canReschedule }
      waitingOrRunning(startNow, listOfNotNull(startNow), capabilities)
    }
    is DownloadState.Completed -> completed(capabilities, fileMissing)
    is DownloadState.Failed -> failed(request, state, device, retryCount)
    is DownloadState.Canceled -> restartable()
  }
}

private fun waitingOrRunning(
  primary: RowAction?,
  controls: List<RowAction>,
  capabilities: RowCapabilities,
): TaskActions {
  val menu = controls + listOfNotNull(
    RowAction.SpeedLimit,
    RowAction.Connections,
    RowAction.Priority,
    RowAction.StartLater.takeIf { capabilities.canReschedule },
    RowAction.SendTo,
    RowAction.CopyLink,
    RowAction.Details,
    RowAction.StopAndDiscard,
    RowAction.Remove
  )
  return TaskActions(primary, listOfNotNull(primary), menu)
}

private fun completed(capabilities: RowCapabilities, fileMissing: Boolean): TaskActions {
  val local = !capabilities.isRemote
  if (local && fileMissing) return restartable()
  val canOpen = capabilities.canOpenFiles
  val removeWithFiles =
    if (capabilities.canTrash) RowAction.RemoveAndTrash else RowAction.RemoveAndDelete
  val menu = listOfNotNull(
    RowAction.Open.takeIf { canOpen },
    RowAction.ShowInFolder.takeIf { canOpen },
    RowAction.CopyLink,
    RowAction.CopyPath,
    RowAction.SendTo,
    RowAction.DownloadAgain,
    RowAction.Remove,
    removeWithFiles
  )
  return if (canOpen) {
    TaskActions(RowAction.Open, listOf(RowAction.Open, RowAction.ShowInFolder), menu)
  } else {
    TaskActions(RowAction.CopyPath, listOf(RowAction.CopyPath), menu)
  }
}

private fun failed(
  request: DownloadRequest,
  state: DownloadState.Failed,
  device: DeviceInfo,
  retryCount: Int,
): TaskActions {
  val copy = state.error.toCopy(request, retryCount, device)
  val fixes = listOf(copy.primary) + copy.secondary
  // When only a fresh start can work, resuming would fail again.
  val canResume = copy.primary != RowAction.DownloadAgain
  val others = listOfNotNull(
    RowAction.Retry.takeIf { canResume },
    RowAction.RetryWithOptions.takeUnless { RowAction.EditLink in fixes },
    RowAction.CopyError,
    RowAction.CopyLink,
    RowAction.DownloadAgain,
    RowAction.FindAnotherSource.takeIf { device.capabilities.canDiscover },
    RowAction.SendTo
  )
  val menu = (fixes + others).distinct() + RowAction.Remove
  return TaskActions(copy.primary, listOf(copy.primary), menu)
}

private fun restartable(): TaskActions = TaskActions(
  primary = RowAction.DownloadAgain,
  hover = listOf(RowAction.DownloadAgain),
  menu = listOf(RowAction.DownloadAgain, RowAction.CopyLink, RowAction.SendTo, RowAction.Remove),
)
