package com.linroid.ketch.app.ui.inspector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.api.torrent.TorrentActivity
import com.linroid.ketch.api.torrent.TorrentCapability
import com.linroid.ketch.api.torrent.TorrentController
import com.linroid.ketch.api.torrent.TorrentCounters
import com.linroid.ketch.api.torrent.TorrentSnapshot
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.speedText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.actions.RowActionRunner
import com.linroid.ketch.app.ui.inspector.tabs.rememberFeatures
import com.linroid.ketch.app.ui.list.TaskCommand
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.inspector_seeding_start
import ketch.app.shared.generated.resources.inspector_seeding_status
import ketch.app.shared.generated.resources.inspector_seeding_stop
import org.jetbrains.compose.resources.stringResource

private val log = KetchLogger("Seeding")

/**
 * Seeding of a finished torrent on a device whose torrents [TorrentController] runs commands:
 * while it shares its files, "Seeding · ↑ 1.2 MB/s · 3.4 GB uploaded" with Stop seeding;
 * otherwise Seed, while the device can seed (its upload setting allows it, read again as the
 * torrent's activity changes). Nothing on other devices.
 *
 * The task is followed through one [TorrentController.observe] subscription while this shows;
 * commands carry a new idempotency key and the revision of the last snapshot.
 */
@Composable
internal fun SeedingControl(
  state: AppState,
  row: TaskRow,
  runner: RowActionRunner,
  pending: Set<Pair<TaskKey, String>>,
) {
  if (row.state !is DownloadState.Completed || !row.isTorrent) return
  val features = rememberFeatures(state, row)
  val controller = remember(features, row.key) { state.torrentControllerOf(row.key.deviceId) }
    ?: return
  var snapshot by remember(row.key, controller) { mutableStateOf<TorrentSnapshot?>(null) }
  var canSeed by remember(row.key, controller) { mutableStateOf(false) }
  LaunchedEffect(row.key, controller) {
    catchingUnlessCancelled { controller.observe(row.key.taskId).collect { snapshot = it } }
      .onFailure { e ->
        log.d { "Stopped following taskId=${row.key.taskId}: ${e.describeCauses()}" }
      }
  }
  val activity = snapshot?.activity
  LaunchedEffect(row.key, controller, activity) {
    canSeed = catchingUnlessCancelled {
      controller.capabilities().supports(TorrentCapability.SEEDING)
    }.getOrDefault(false)
  }
  val seeding = activity?.let { it == TorrentActivity.SEEDING } ?: row.state.seeding
  if (!seeding && !canSeed) return
  val busy = pending.any { (key, label) ->
    key == row.key && (label == TaskCommand.Seed.key || label == TaskCommand.StopSeeding.key)
  }
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier.fillMaxWidth(),
  ) {
    if (seeding) {
      Text(
        text = seedingStatus(snapshot?.counters).resolve(),
        style = KetchTheme.typography.caption,
        color = KetchTheme.colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
    } else {
      Spacer(Modifier.weight(1f))
    }
    KetchButton(
      text = stringResource(
        if (seeding) Res.string.inspector_seeding_stop else Res.string.inspector_seeding_start,
      ),
      onClick = {
        runner.commands.setSeeding(row, seeding = !seeding, revision = snapshot?.revision)
      },
      variant = KetchButtonVariant.Secondary,
      size = KetchButtonSize.Small,
      leadingIcon = if (seeding) KetchIcon.Stop else KetchIcon.Send,
      loading = busy,
    )
  }
}

/** "Seeding · ↑ 1.2 MB/s · 3.4 GB uploaded", with "–" for what the device does not count. */
internal fun seedingStatus(counters: TorrentCounters?): UiText {
  val speed = counters?.uploadBytesPerSecond?.let(::speedText) ?: verbatim(UNKNOWN)
  val uploaded = counters?.uploadedPayloadBytes?.let(::sizeText) ?: verbatim(UNKNOWN)
  return Res.string.inspector_seeding_status.text(speed, uploaded)
}

private const val UNKNOWN = "–"
