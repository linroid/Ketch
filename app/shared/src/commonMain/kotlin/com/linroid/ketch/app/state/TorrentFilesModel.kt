package com.linroid.ketch.app.state

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.DownloadTask
import com.linroid.ketch.api.SourceFile
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.torrent.TorrentController
import com.linroid.ketch.api.torrent.TorrentPageRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job

/**
 * The files of one torrent task as the inspector's Files tab lists and changes them.
 *
 * Names come from the task's [DownloadRequest.resolvedSource]. A task without them, such as one
 * an older version saved, has them read from its device's [controller] with [load], at most
 * [MAX_PAGES] pages of [PAGE_SIZE]; files past those stay unnamed.
 *
 * Checking and unchecking files builds [pending] over the files the task downloads (see
 * [appliedSelection]); the last checked file cannot be unchecked ([keepOne]). [apply] saves the
 * choice with [DownloadTask.selectFiles], which also starts a task that waits for its files. A
 * failure goes back to the files the task downloads and is passed to [onFailure].
 *
 * @param launch runs [apply]'s call, in a scope that outlives the tab, such as
 *   [AppState.launchCommand].
 */
@Stable
internal class TorrentFilesModel(
  private val task: DownloadTask,
  private val controller: TorrentController?,
  private val launch: (suspend CoroutineScope.() -> Unit) -> Job,
  private val onFailure: (Throwable) -> Unit = {},
) {
  private val log = KetchLogger("TorrentFiles")

  /** The files [load] read from [controller], in torrent order; `null` until then. */
  var listed: List<SourceFile>? by mutableStateOf(null)
    private set

  /** The checked files while they differ from what the task downloads; `null` otherwise. */
  var pending: Set<String>? by mutableStateOf(null)
    private set

  /** Whether [apply] is saving a choice. */
  var applying: Boolean by mutableStateOf(false)
    private set

  /** Whether the last click tried to uncheck the last checked file, which stays checked. */
  var keepOne: Boolean by mutableStateOf(false)
    private set

  /** The files of [request]'s torrent: its resolved list, else the one [load] read. */
  fun filesOf(request: DownloadRequest): List<SourceFile>? =
    request.resolvedSource?.files?.takeIf { it.isNotEmpty() } ?: listed

  /** Whether [request] names no files and [load] could read them from the device. */
  fun needsNames(request: DownloadRequest): Boolean =
    controller != null && request.resolvedSource?.files.isNullOrEmpty() && listed == null

  /**
   * Reads every file of the task from [controller], page by page in torrent order, into
   * [listed]. A device that cannot list them yet, such as a magnet still looking for its
   * metadata, leaves it as it was.
   */
  suspend fun load() {
    val controller = controller ?: return
    catchingUnlessCancelled {
      val files = ArrayList<SourceFile>()
      var cursor: String? = null
      var pages = 0
      do {
        val result = controller.files(task.taskId, TorrentPageRequest(PAGE_SIZE, cursor))
          ?: return@catchingUnlessCancelled null
        result.files.mapTo(files) { SourceFile(it.id, it.path, it.size) }
        cursor = result.nextCursor
        pages++
      } while (cursor != null && pages < MAX_PAGES)
      files
    }.onSuccess { files ->
      if (files != null) listed = files
    }.onFailure { e ->
      log.d { "Couldn't list the files of taskId=${task.taskId}: ${e.describeCauses()}" }
    }
  }

  /** The files checked while the task downloads [applied]. */
  fun checked(applied: Set<String>): Set<String> = pending ?: applied

  /**
   * Whether the choice differs from [applied] and can be applied: always for a task that
   * [waits][DownloadState.awaitsFileSelection] for its files.
   */
  fun isDirty(applied: Set<String>, waiting: Boolean): Boolean =
    waiting || pending.let { it != null && it != applied }

  /**
   * Checks the files of [fileIds] when any of them is unchecked, else unchecks them all, unless
   * that would leave nothing checked.
   */
  fun toggle(fileIds: List<String>, applied: Set<String>) {
    val next = com.linroid.ketch.app.state.toggle(fileIds, checked(applied))
    if (next.isEmpty()) {
      keepOne = true
      return
    }
    keepOne = false
    pending = next.takeIf { it != applied }
  }

  /** Goes back to the files the task downloads. */
  fun reset() {
    pending = null
    keepOne = false
  }

  /** Forgets [pending] once the task downloads exactly those files. */
  fun sync(applied: Set<String>) {
    if (pending == applied) pending = null
  }

  /**
   * Has the task download the checked files, [applied] when nothing changed, which starts a
   * task that waits for its files. Returns `null` while a choice is being saved.
   */
  fun apply(applied: Set<String>): Job? {
    val target = checked(applied)
    if (applying || target.isEmpty()) return null
    applying = true
    keepOne = false
    return launch {
      catchingUnlessCancelled { task.selectFiles(target) }
        .onFailure { e ->
          log.w { "Couldn't change the files of taskId=${task.taskId}: ${e.describeCauses()}" }
          pending = null
          onFailure(e)
        }
      applying = false
    }
  }

  companion object {
    /** The most pages [load] reads. */
    const val MAX_PAGES: Int = 10

    /** Files per page [load] asks for, the most a device sends. */
    const val PAGE_SIZE: Int = 1000
  }
}

/**
 * The files of [all] that a task downloading [request] while in [state] downloads, and so shows
 * checked: its selection, or every file when it chose none or [waits][awaitsFileSelection] for a
 * choice, which starts from every file.
 */
internal fun appliedSelection(
  request: DownloadRequest,
  state: DownloadState,
  all: List<SourceFile>,
): Set<String> = when {
  state.awaitsFileSelection || request.selectedFileIds.isEmpty() -> all.mapTo(HashSet()) { it.id }
  else -> request.selectedFileIds
}
