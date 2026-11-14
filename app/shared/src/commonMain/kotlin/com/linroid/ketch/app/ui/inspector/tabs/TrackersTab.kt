package com.linroid.ketch.app.ui.inspector.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.torrent.api.TorrentTracker
import com.linroid.ketch.torrent.api.TorrentTrackerState
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.inspector_tracker_announcing
import ketch.app.shared.generated.resources.inspector_tracker_failed
import ketch.app.shared.generated.resources.inspector_tracker_idle
import ketch.app.shared.generated.resources.inspector_tracker_leechers
import ketch.app.shared.generated.resources.inspector_tracker_peers
import ketch.app.shared.generated.resources.inspector_tracker_seeders
import ketch.app.shared.generated.resources.inspector_tracker_timed_out
import ketch.app.shared.generated.resources.inspector_tracker_working
import ketch.app.shared.generated.resources.inspector_trackers_empty
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

private val log = KetchLogger("Inspector")

/** How often the tab asks the task's device for its trackers while it shows. */
private val REFRESH = 3.seconds

/**
 * The Trackers tab of a torrent: each of its own trackers by address, with how it last answered,
 * the peers it named, the swarm it counts and what it said when it refused. The device is asked
 * again every few seconds while the tab shows.
 */
@Composable
fun TrackersTab(state: AppState, row: TaskRow, modifier: Modifier = Modifier) {
  val features = rememberFeatures(state, row)
  val controller = remember(features, row.key) { state.torrentControllerOf(row.key.deviceId) }
  var trackers by remember(row.key, controller) { mutableStateOf<List<TorrentTracker>?>(null) }
  LaunchedEffect(row.key, controller) {
    if (controller == null) return@LaunchedEffect
    while (true) {
      catchingUnlessCancelled { controller.trackers(row.key.taskId) }
        .onSuccess { trackers = it.orEmpty() }
        .onFailure { e ->
          log.d { "No trackers of taskId=${row.key.taskId}: ${e.describeCauses()}" }
        }
      delay(REFRESH)
    }
  }
  TrackersTabContent(trackers, modifier)
}

/** [TrackersTab] for [trackers], `null` until the device answered. */
@Composable
internal fun TrackersTabContent(trackers: List<TorrentTracker>?, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  Column(
    modifier = modifier.fillMaxWidth(),
    verticalArrangement = Arrangement.spacedBy(spacing.s3),
  ) {
    if (trackers == null) return@Column
    if (trackers.isEmpty()) {
      Text(
        text = stringResource(Res.string.inspector_trackers_empty),
        style = type.caption,
        color = colors.textTertiary,
      )
    }
    for (tracker in trackers) {
      val view = trackerView(tracker)
      Column(verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
        Text(
          text = tracker.endpoint,
          style = type.monoS,
          color = colors.textPrimary,
          maxLines = 1,
          overflow = TextOverflow.MiddleEllipsis,
        )
        Text(
          text = view.summary.map { it.resolve() }.joinToString(" · "),
          style = type.caption,
          color = when (view.tone) {
            TrackerTone.Good -> colors.status.completed.color
            TrackerTone.Bad -> colors.status.failed.color
            TrackerTone.Neutral -> colors.textSecondary
          },
        )
        if (view.message != null) {
          Text(text = view.message, style = type.caption, color = colors.textTertiary)
        }
      }
    }
  }
}

/** How a tracker's summary line is colored. */
internal enum class TrackerTone { Good, Bad, Neutral }

/**
 * What the tab says of one tracker.
 *
 * @property summary its state, then the peers it named and the swarm it counts, when known
 * @property message what the tracker said, when it said anything
 */
internal data class TrackerView(
  val summary: List<UiText>,
  val tone: TrackerTone,
  val message: String?,
)

/** What [TrackersTab] says of [tracker]. */
internal fun trackerView(tracker: TorrentTracker): TrackerView {
  val state = when (tracker.state) {
    TorrentTrackerState.IDLE -> Res.string.inspector_tracker_idle
    TorrentTrackerState.ANNOUNCING -> Res.string.inspector_tracker_announcing
    TorrentTrackerState.WORKING -> Res.string.inspector_tracker_working
    TorrentTrackerState.FAILED -> Res.string.inspector_tracker_failed
    TorrentTrackerState.TIMED_OUT -> Res.string.inspector_tracker_timed_out
  }
  val summary = buildList {
    add(state.text())
    tracker.peers?.let { add(Res.string.inspector_tracker_peers.text(it)) }
    tracker.seeders?.let { add(Res.string.inspector_tracker_seeders.text(it)) }
    tracker.leechers?.let { add(Res.string.inspector_tracker_leechers.text(it)) }
  }
  val tone = when (tracker.state) {
    TorrentTrackerState.WORKING -> TrackerTone.Good
    TorrentTrackerState.FAILED, TorrentTrackerState.TIMED_OUT -> TrackerTone.Bad
    TorrentTrackerState.IDLE, TorrentTrackerState.ANNOUNCING -> TrackerTone.Neutral
  }
  return TrackerView(summary, tone, tracker.message?.takeIf { it.isNotBlank() })
}
