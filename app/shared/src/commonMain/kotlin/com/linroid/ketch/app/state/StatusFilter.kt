package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.util.SearchTarget
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.status_tab_all
import ketch.app.shared.generated.resources.status_tab_done
import ketch.app.shared.generated.resources.status_tab_downloading
import ketch.app.shared.generated.resources.status_tab_failed
import ketch.app.shared.generated.resources.status_tab_paused
import ketch.app.shared.generated.resources.status_tab_waiting
import org.jetbrains.compose.resources.StringResource

/**
 * Status tabs of the Downloads page. The Pulse bar, the tray and device rows count tasks with
 * the same definitions.
 *
 */
enum class StatusFilter(private val resource: StringResource) {
  All(Res.string.status_tab_all),
  Downloading(Res.string.status_tab_downloading),

  /** Queued for a free slot, paused for an urgent download, or scheduled to start later. */
  Waiting(Res.string.status_tab_waiting),
  Paused(Res.string.status_tab_paused),
  Done(Res.string.status_tab_done),

  /** Failed or canceled. */
  Failed(Res.string.status_tab_failed);

  /** Name shown on the tab. */
  val label: UiText get() = resource.text()

  /**
   * Whether a task in [state] belongs on this tab. A queued task that is [starting]
   * ([isStarting]) holds a slot, so it counts as downloading.
   */
  fun matches(state: DownloadState, starting: Boolean = false): Boolean = when (this) {
    All -> true
    Downloading -> state is DownloadState.Downloading || starting
    Waiting -> !starting && (state.waitsInQueue || state is DownloadState.Scheduled)
    Paused -> state.isPausedUntilResumed
    Done -> state is DownloadState.Completed
    Failed -> state is DownloadState.Failed || state is DownloadState.Canceled
  }

  /** Whether [task] belongs on this tab. */
  fun matches(task: SearchTarget): Boolean = matches(task.state, task.isStarting)

  /** Number of [tasks] that belong on this tab. */
  fun count(tasks: Collection<SearchTarget>): Int =
    if (this == All) tasks.size else tasks.count { matches(it) }

  companion object {
    /** Number of [tasks] on each tab. */
    fun counts(tasks: Collection<SearchTarget>): Map<StatusFilter, Int> =
      entries.associateWith { it.count(tasks) }
  }
}
