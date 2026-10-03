package com.linroid.ketch.app.ui.list

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.state.LocalAppState
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.util.RowStatus
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.row_missing_file
import ketch.app.shared.generated.resources.row_status_file_missing
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlin.time.Duration.Companion.milliseconds

/**
 * How a row plays its download's completion: the lane strip stays for its sheen while
 * [showsStrip], then the file chip shows a check while [showsCheck], for 1.2 s.
 */
@Stable
internal class RowCompletion {
  /** Whether the row keeps its lane strip for the completion sheen. */
  var showsStrip: Boolean by mutableStateOf(false)
    private set

  /** Whether the file chip shows a check in place of its glyph. */
  var showsCheck: Boolean by mutableStateOf(false)
    private set

  internal var wasRunning: Boolean = false

  internal fun start() {
    showsStrip = true
  }

  /** Called by the lane strip once its sheen has played; the chip then shows the check. */
  fun onSheenShown() {
    if (!showsStrip) return
    showsStrip = false
    showsCheck = true
  }

  internal fun endCheck() {
    showsCheck = false
  }
}

/**
 * The [RowCompletion] of [row], which starts when the row is seen to go from downloading or
 * paused to completed. A row that is already complete when it appears plays nothing.
 */
@Composable
internal fun rememberRowCompletion(row: TaskRow): RowCompletion {
  val completion = remember(row.key) { RowCompletion() }
  val running = row.state is DownloadState.Downloading || row.state is DownloadState.Paused
  val completed = row.state is DownloadState.Completed
  LaunchedEffect(completion, completed) {
    if (completed && completion.wasRunning) completion.start()
  }
  SideEffect { if (running || !completed) completion.wasRunning = running }
  LaunchedEffect(completion, completion.showsCheck) {
    if (!completion.showsCheck) return@LaunchedEffect
    delay(CHECK_DURATION)
    completion.endCheck()
  }
  return completion
}

/** Whether [row] shows its lane strip: while it runs, and through its completion sheen. */
internal fun showsLanes(row: TaskRow, completion: RowCompletion): Boolean =
  row.state is DownloadState.Downloading || row.state is DownloadState.Paused ||
    (row.state is DownloadState.Completed && completion.showsStrip)

/**
 * Where [row]'s stalled connections start, whose write heads its lane strip draws in amber; read
 * from the app's speed history.
 */
@Composable
internal fun rememberStalledLanes(row: TaskRow): Set<Long> {
  val history = LocalAppState.current.speedHistory
  val stalled by remember(history, row.key) {
    history.rates
      .map { rates ->
        rates[row.key].orEmpty().filter { it.stalledFor != null }.mapTo(HashSet()) { it.start }
      }
      .distinctUntilChanged()
  }.collectAsState(emptySet())
  return stalled
}

/**
 * [row] as the list shows it once its finished file turned out to be [missing]: a warning dot,
 * "File missing" and "File moved or deleted". Only local files are checked.
 */
internal fun withMissingFile(row: TaskRow, missing: Boolean): TaskRow {
  if (!missing || row.state !is DownloadState.Completed || row.device.capabilities.isRemote) {
    return row
  }
  val content = row.content.copy(
    status = RowStatus.FileMissing,
    statusText = Res.string.row_status_file_missing.text(),
    detail = Res.string.row_missing_file.text(),
  )
  return row.copy(content = content)
}

private val CHECK_DURATION = 1200.milliseconds
