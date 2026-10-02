package com.linroid.ketch.app.ui.toolbar

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.theme.KetchTheme

/**
 * What the bulk actions of the Downloads header can act on: every task of the device, not only
 * those on the tab shown.
 *
 * @property canPause whether a task downloads or waits in the queue.
 * @property canResume whether a task is paused.
 * @property failed number of failed tasks, which Retry all failed retries.
 * @property finished number of completed tasks, which Clear finished takes off the list.
 */
@Immutable
data class BulkActions(
  val canPause: Boolean = false,
  val canResume: Boolean = false,
  val failed: Int = 0,
  val finished: Int = 0,
) {
  companion object {
    /** The bulk actions for tasks in [states]. */
    fun of(states: Collection<DownloadState>): BulkActions = BulkActions(
      canPause = states.any { it is DownloadState.Downloading || it is DownloadState.Queued },
      canResume = states.any { it is DownloadState.Paused },
      failed = states.count { it is DownloadState.Failed },
      finished = states.count { it is DownloadState.Completed },
    )
  }
}

/** Label of the item that clears [finished] completed downloads off the list. */
internal fun clearFinishedLabel(finished: Int): String =
  if (finished > 0) "Clear $finished finished" else "Clear finished"

/**
 * Pause all and Resume all, which stay in place and are disabled when there is nothing to act
 * on, then a "⋯" menu with Retry all failed and Clear finished. Clearing is undone from its
 * toast, so nothing destructive sits next to Add.
 */
@Composable
fun BatchActionBar(
  actions: BulkActions,
  onPauseAll: () -> Unit,
  onResumeAll: () -> Unit,
  onRetryFailed: () -> Unit,
  onClearCompleted: () -> Unit,
  modifier: Modifier = Modifier,
) {
  var menuOpen by remember { mutableStateOf(false) }
  Row(
    modifier = modifier,
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s0_5),
  ) {
    KetchIconButton(
      command = KetchCommands.PauseAll,
      enabled = actions.canPause,
      onClick = onPauseAll,
    )
    KetchIconButton(
      command = KetchCommands.ResumeAll,
      enabled = actions.canResume,
      onClick = onResumeAll,
    )
    Box {
      KetchIconButton(
        icon = KetchIcon.More,
        contentDescription = "More actions",
        onClick = { menuOpen = true },
      )
      KetchMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
        item(
          command = KetchCommands.RetryFailed,
          enabled = actions.failed > 0,
          onClick = onRetryFailed,
        )
        divider()
        item(
          label = clearFinishedLabel(actions.finished),
          icon = KetchIcon.Trash,
          enabled = actions.finished > 0,
          onClick = onClearCompleted,
        )
      }
    }
  }
}
