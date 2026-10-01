package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.util.toCopy

/**
 * Something the user can do with a task. Row buttons, hover actions, the context menu, the
 * selection bar, the command palette, shortcuts, the tray and notifications all take their
 * actions from [taskActions], so a task offers the same choices everywhere.
 *
 * @property label menu text.
 * @property destructive whether the action discards the task or its progress. Menus put these
 *   last, after a divider.
 */
sealed class RowAction(val label: String, val destructive: Boolean = false) {
  /** Pauses a downloading or queued task. */
  data object Pause : RowAction("Pause")

  /** Resumes a paused task. */
  data object Resume : RowAction("Resume")

  /** Pauses a stalled task and resumes it, so it opens fresh connections. */
  data object Reconnect : RowAction("Reconnect")

  /**
   * Starts a waiting task now: a queued task becomes urgent, which may pause another, and a
   * scheduled task is rescheduled to start immediately.
   */
  data object StartNow : RowAction("Start now")

  /** Opens the speed limit choices. */
  data object SpeedLimit : RowAction("Speed limit")

  /** Opens the connection count choices, or the peer limit of a torrent. */
  data object Connections : RowAction("Connections")

  /** Opens the priority choices. */
  data object Priority : RowAction("Priority")

  /** Opens the choices of when to start. */
  data object StartLater : RowAction("Start later")

  /** Opens the devices to send the task to. */
  data object SendTo : RowAction("Send to")

  /** Copies the task's link. */
  data object CopyLink : RowAction("Copy link")

  /** Opens the task in the inspector. */
  data object Details : RowAction("Details")

  /** Opens the downloaded file. */
  data object Open : RowAction("Open")

  /** Shows the file, or the folder it belongs in, in the file manager. */
  data object ShowInFolder : RowAction("Show in folder")

  /** Copies the path of the downloaded file. */
  data object CopyPath : RowAction("Copy file path")

  /** Adds the task's request again from the start and removes this task. */
  data object DownloadAgain : RowAction("Download again")

  /** Resumes a failed task from where it stopped. */
  data object Retry : RowAction("Retry")

  /** Opens the add sheet with the task's options, to retry with changes. */
  data object RetryWithOptions : RowAction("Retry with options…")

  /** Opens the add sheet to change the link of a task the server turned away. */
  data object EditLink : RowAction("Edit link…")

  /**
   * Lowers the task to [connections] connections and resumes it.
   *
   * @property connections connection count to retry with.
   */
  data class RetryWithConnections(val connections: Int) : RowAction(
    if (connections == 1) "Retry with 1 connection" else "Retry with $connections connections",
  )

  /** Opens the add sheet with user name and password fields. */
  data object EnterCredentials : RowAction("Enter credentials…")

  /** Opens Discover to look for the file elsewhere, searching for its name. */
  data object FindAnotherSource : RowAction("Find another source")

  /** Opens the page the link was captured from (its `Referer`). */
  data object OpenSourcePage : RowAction("Open source page")

  /** Copies the error's title and hint. */
  data object CopyError : RowAction("Copy error")

  /** Copies technical details for a bug report. */
  data object CopyDetails : RowAction("Copy details")

  /** Cancels the task after confirmation; its progress cannot be resumed. */
  data object StopAndDiscard : RowAction("Stop and discard progress…", destructive = true)

  /** Removes the task from the list and keeps its files, with Undo. */
  data object Remove : RowAction("Remove from list", destructive = true)

  /** Removes the task and moves its files to the Trash, after confirmation. */
  data object RemoveAndTrash : RowAction("Remove and trash file…", destructive = true)

  /** Removes the task and deletes its files, after confirmation. */
  data object RemoveAndDelete : RowAction("Remove and delete files…", destructive = true)
}

/**
 * What the app can do with the tasks of one device.
 *
 * @property isRemote whether the device is another Ketch instance reached over the network.
 * @property canReschedule whether its tasks can be rescheduled; remote ones cannot yet.
 * @property canOpenFiles whether this app can open and reveal its downloaded files.
 * @property canTrash whether removed files can go to the Trash instead of being deleted.
 * @property canDiscover whether AI discovery is usable here, to find another source for a link
 *   that no longer works.
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
  val name: String,
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
 * @param fileMissing whether a completed task's file is gone; only checked on local devices.
 */
fun taskActions(
  request: DownloadRequest,
  state: DownloadState,
  device: DeviceInfo,
  retryCount: Int = 0,
  stalled: Boolean = false,
  fileMissing: Boolean = false,
): TaskActions {
  val capabilities = device.capabilities
  return when (state) {
    is DownloadState.Downloading -> {
      val primary = if (stalled) RowAction.Reconnect else RowAction.Pause
      waitingOrRunning(primary, listOf(primary, RowAction.Pause).distinct(), capabilities)
    }
    is DownloadState.Paused ->
      waitingOrRunning(RowAction.Resume, listOf(RowAction.Resume), capabilities)
    is DownloadState.Queued -> waitingOrRunning(
      RowAction.StartNow,
      listOf(RowAction.Pause, RowAction.StartNow),
      capabilities,
    )
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
    RowAction.Remove,
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
    removeWithFiles,
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
    RowAction.SendTo,
  )
  val menu = (fixes + others).distinct() + RowAction.Remove
  return TaskActions(copy.primary, listOf(copy.primary), menu)
}

private fun restartable(): TaskActions = TaskActions(
  primary = RowAction.DownloadAgain,
  hover = listOf(RowAction.DownloadAgain),
  menu = listOf(RowAction.DownloadAgain, RowAction.CopyLink, RowAction.SendTo, RowAction.Remove),
)
