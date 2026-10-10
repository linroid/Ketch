package com.linroid.ketch.app.ui.inspector.tabs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.KetchFeatures
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SpeedHistory
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.TimelineEntry
import com.linroid.ketch.app.state.awaitsFileSelection
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.inspector_tab_activity
import ketch.app.shared.generated.resources.inspector_tab_connections
import ketch.app.shared.generated.resources.inspector_tab_files
import ketch.app.shared.generated.resources.inspector_tab_overview
import org.jetbrains.compose.resources.StringResource

/** A tab of the task inspector. */
enum class InspectorTab(private val resource: StringResource) {
  /** State, controls and details. */
  Overview(Res.string.inspector_tab_overview),

  /** Each connection of an HTTP or FTP download; see [ConnectionsTab]. */
  Connections(Res.string.inspector_tab_connections),

  /** Each file of a torrent; see [FilesTab]. */
  Files(Res.string.inspector_tab_files),

  /** Speed over the last five minutes and the session's events; see [ActivityTab]. */
  Activity(Res.string.inspector_tab_activity);

  /** The tab's name. */
  val title: UiText get() = resource.text()
}

/**
 * The tabs the inspector offers for [row], in order, leaving out any with nothing to show:
 * Overview; Connections while an HTTP or FTP task has unfinished segments, or Files once a
 * torrent's files are known, or while it waits for them to be chosen on a device that lists
 * them ([KetchFeatures.TORRENT_CONTROL] among its [features]); and Activity once the task has a
 * speed [history] or a [timeline].
 */
fun inspectorTabs(
  row: TaskRow,
  history: SpeedHistory?,
  timeline: List<TimelineEntry>,
  features: Set<String> = emptySet(),
): List<InspectorTab> = buildList {
  add(InspectorTab.Overview)
  if (row.isTorrent) {
    val listsFiles = row.state.awaitsFileSelection && KetchFeatures.TORRENT_CONTROL in features
    if (torrentFileCount(row, filesSelectable(row, features)) > 0 || listsFiles) {
      add(InspectorTab.Files)
    }
  } else if (connectionsCount(row) > 0) {
    add(InspectorTab.Connections)
  }
  if (history != null || timeline.isNotEmpty()) add(InspectorTab.Activity)
}

/**
 * The number after the tab's [InspectorTab.title] for [row] on a device that reports
 * [features], such as 8 in "Connections 8": the connections still downloading, or the torrent's
 * files; `null` for the other tabs and while there are none.
 */
fun InspectorTab.count(row: TaskRow, features: Set<String> = emptySet()): Int? = when (this) {
  InspectorTab.Connections -> connectionsCount(row)
  InspectorTab.Files -> torrentFileCount(row, filesSelectable(row, features)).takeIf { it > 0 }
  InspectorTab.Overview, InspectorTab.Activity -> null
}

/** What the device of [row] reports in its features, as it changes. */
@Composable
fun rememberFeatures(state: AppState, row: TaskRow): Set<String> {
  // Collected so the tabs follow what a remote device reports it supports.
  val presence by state.instanceManager.presence.collectAsState()
  val deviceId = row.key.deviceId
  return remember(presence, deviceId) { state.featuresOf(deviceId) }
}

/** [inspectorTabs] for [row], following the app's speed history and timelines. */
@Composable
fun rememberInspectorTabs(state: AppState, row: TaskRow): List<InspectorTab> {
  val histories by state.speedHistory.histories.collectAsState()
  val timelines by state.speedHistory.timelines.collectAsState()
  val features = rememberFeatures(state, row)
  val history = histories[row.key]
  val timeline = timelines[row.key].orEmpty()
  val hasActivity = history != null || timeline.isNotEmpty()
  return remember(row, hasActivity, features) { inspectorTabs(row, history, timeline, features) }
}

/** Unfinished segments of an HTTP or FTP task that has not completed or been canceled. */
private fun connectionsCount(row: TaskRow): Int = when (row.state) {
  is DownloadState.Completed, is DownloadState.Canceled -> 0
  else -> row.segments.count { !it.isComplete }
}
