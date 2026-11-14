package com.linroid.ketch.app.ui.inspector.tabs

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchTextField
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.torrent.api.TorrentCapability
import com.linroid.ketch.torrent.api.TorrentCommandError
import com.linroid.ketch.torrent.api.TorrentCommandException
import com.linroid.ketch.torrent.api.TorrentTracker
import com.linroid.ketch.torrent.api.TorrentTrackerEdit
import com.linroid.ketch.torrent.api.TorrentTrackerState
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_add
import ketch.app.shared.generated.resources.inspector_tracker_announcing
import ketch.app.shared.generated.resources.inspector_tracker_failed
import ketch.app.shared.generated.resources.inspector_tracker_idle
import ketch.app.shared.generated.resources.inspector_tracker_leechers
import ketch.app.shared.generated.resources.inspector_tracker_peers
import ketch.app.shared.generated.resources.inspector_tracker_seeders
import ketch.app.shared.generated.resources.inspector_tracker_timed_out
import ketch.app.shared.generated.resources.inspector_tracker_working
import ketch.app.shared.generated.resources.inspector_trackers_add
import ketch.app.shared.generated.resources.inspector_trackers_add_hint
import ketch.app.shared.generated.resources.inspector_trackers_add_placeholder
import ketch.app.shared.generated.resources.inspector_trackers_empty
import ketch.app.shared.generated.resources.inspector_trackers_failed
import ketch.app.shared.generated.resources.inspector_trackers_invalid
import ketch.app.shared.generated.resources.settings_torrent_remove
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.StringResource
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
  val editable by produceState(false, controller) {
    value = controller != null && catchingUnlessCancelled {
      controller.capabilities().supports(TorrentCapability.TRACKER_EDIT)
    }.getOrDefault(false)
  }
  var draft by remember(row.key) { mutableStateOf("") }
  var problem by remember(row.key) { mutableStateOf<StringResource?>(null) }
  val scope = rememberCoroutineScope()
  val edit: (TorrentTrackerEdit, Boolean) -> Unit = { change, fromDraft ->
    scope.launch {
      catchingUnlessCancelled { controller?.editTrackers(row.key.taskId, change) }
        .onSuccess {
          if (it != null) trackers = it
          if (fromDraft) draft = ""
          problem = null
        }
        .onFailure { e ->
          log.w { "Trackers of taskId=${row.key.taskId} not changed: ${e.describeCauses()}" }
          val invalid = (e as? TorrentCommandException)?.error == TorrentCommandError.INVALID_INPUT
          problem = if (invalid) {
            Res.string.inspector_trackers_invalid
          } else {
            Res.string.inspector_trackers_failed
          }
        }
    }
  }
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
  val editing = if (!editable) null else TrackerEditing(
    draft = draft,
    problem = problem,
    onDraftChange = {
      draft = it
      problem = null
    },
    onAdd = { edit(TorrentTrackerEdit(add = trackerUrls(draft)), true) },
    onRemove = { edit(TorrentTrackerEdit(remove = listOf(it.endpoint)), false) },
  )
  TrackersTabContent(trackers, modifier, editing)
}

/** The announce URLs typed into the tab's field: separated by spaces, commas or new lines. */
internal fun trackerUrls(text: String): List<String> =
  text.split(' ', ',', '\n', '\r', '\t').filter { it.isNotEmpty() }.distinct()

/**
 * What lets the tab change a torrent's trackers, on devices that can.
 *
 * @property draft the announce URLs typed so far
 * @property problem why the last change did not happen, until the draft changes
 */
internal class TrackerEditing(
  val draft: String,
  val problem: StringResource?,
  val onDraftChange: (String) -> Unit,
  val onAdd: () -> Unit,
  val onRemove: (TorrentTracker) -> Unit,
)

/**
 * [TrackersTab] for [trackers], `null` until the device answered; with [editing], each tracker
 * can be removed and new ones added below them.
 */
@Composable
internal fun TrackersTabContent(
  trackers: List<TorrentTracker>?,
  modifier: Modifier = Modifier,
  editing: TrackerEditing? = null,
) {
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
        Row(verticalAlignment = Alignment.CenterVertically) {
          Text(
            text = tracker.endpoint,
            modifier = Modifier.weight(1f),
            style = type.monoS,
            color = colors.textPrimary,
            maxLines = 1,
            overflow = TextOverflow.MiddleEllipsis,
          )
          if (editing != null) {
            KetchIconButton(
              icon = KetchIcon.Close,
              onClick = { editing.onRemove(tracker) },
              size = KetchButtonSize.Small,
              contentDescription =
                stringResource(Res.string.settings_torrent_remove, tracker.endpoint),
            )
          }
        }
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
    if (editing != null) {
      Row(
        verticalAlignment = Alignment.Bottom,
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      ) {
        KetchTextField(
          value = editing.draft,
          onValueChange = editing.onDraftChange,
          modifier = Modifier.weight(1f),
          placeholder = stringResource(Res.string.inspector_trackers_add_placeholder),
          mono = true,
          label = stringResource(Res.string.inspector_trackers_add),
          error = editing.problem?.let { stringResource(it) },
          maxLines = 4,
        )
        KetchButton(
          text = stringResource(Res.string.action_add),
          onClick = editing.onAdd,
          variant = KetchButtonVariant.Secondary,
          enabled = editing.draft.isNotBlank(),
        )
      }
      Text(
        text = stringResource(Res.string.inspector_trackers_add_hint),
        style = type.caption,
        color = colors.textTertiary,
      )
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
