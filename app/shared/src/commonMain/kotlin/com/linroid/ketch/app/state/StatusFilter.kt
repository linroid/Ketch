package com.linroid.ketch.app.state

import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
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

  /** Whether a task in [state] belongs on this tab. */
  fun matches(state: DownloadState): Boolean = when (this) {
    All -> true
    Downloading -> state is DownloadState.Downloading
    Waiting -> state.waitsInQueue || state is DownloadState.Scheduled
    Paused -> state.isPausedUntilResumed
    Done -> state is DownloadState.Completed
    Failed -> state is DownloadState.Failed || state is DownloadState.Canceled
  }

  /** Number of the tasks in [states] that belong on this tab. */
  fun count(states: Collection<DownloadState>): Int =
    if (this == All) states.size else states.count(::matches)

  companion object {
    /** Number of the tasks in [states] on each tab. */
    fun counts(states: Collection<DownloadState>): Map<StatusFilter, Int> =
      entries.associateWith { it.count(states) }
  }
}
