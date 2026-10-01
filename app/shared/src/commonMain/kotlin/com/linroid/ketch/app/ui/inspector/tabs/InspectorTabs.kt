package com.linroid.ketch.app.ui.inspector.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SpeedHistory
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.TimelineEntry

/** A tab of the task inspector. */
enum class InspectorTab(val title: String) {
  /** State, controls and details. */
  Overview("Overview"),

  /** Each connection of an HTTP or FTP download; see [ConnectionsTab]. */
  Connections("Connections"),

  /** Each file of a torrent; see [FilesTab]. */
  Files("Files"),

  /** Speed over the last five minutes and the session's events; see [ActivityTab]. */
  Activity("Activity"),
}

/**
 * The tabs the inspector offers for [row], in order, leaving out any with nothing to show:
 * Overview; Connections while an HTTP or FTP task has unfinished segments, or Files once a
 * torrent's files are known; and Activity once the task has a speed [history] or a [timeline].
 */
fun inspectorTabs(
  row: TaskRow,
  history: SpeedHistory?,
  timeline: List<TimelineEntry>,
): List<InspectorTab> = buildList {
  add(InspectorTab.Overview)
  if (row.isTorrent) {
    if (torrentFiles(row).isNotEmpty()) add(InspectorTab.Files)
  } else if (connectionsCount(row) > 0) {
    add(InspectorTab.Connections)
  }
  if (history != null || timeline.isNotEmpty()) add(InspectorTab.Activity)
}

/**
 * The number after the tab's [InspectorTab.title] for [row], such as 8 in "Connections 8": the
 * connections still downloading, or the torrent's files; `null` for the other tabs.
 */
fun InspectorTab.count(row: TaskRow): Int? = when (this) {
  InspectorTab.Connections -> connectionsCount(row)
  InspectorTab.Files -> torrentFiles(row).size
  InspectorTab.Overview, InspectorTab.Activity -> null
}

/** [inspectorTabs] for [row], following the app's speed history and timelines. */
@Composable
fun rememberInspectorTabs(state: AppState, row: TaskRow): List<InspectorTab> {
  val histories by state.speedHistory.histories.collectAsState()
  val timelines by state.speedHistory.timelines.collectAsState()
  val history = histories[row.key]
  val timeline = timelines[row.key].orEmpty()
  val hasActivity = history != null || timeline.isNotEmpty()
  return remember(row, hasActivity) { inspectorTabs(row, history, timeline) }
}

/** Unfinished segments of an HTTP or FTP task that has not completed or been canceled. */
private fun connectionsCount(row: TaskRow): Int = when (row.state) {
  is DownloadState.Completed, is DownloadState.Canceled -> 0
  else -> row.segments.count { !it.isComplete }
}
