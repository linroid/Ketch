package com.linroid.ketch.app.ui.inspector

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
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
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.log.KetchLogger
import com.linroid.ketch.api.log.describeCauses
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.etaText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.inspector.tabs.filesSelectable
import com.linroid.ketch.app.ui.inspector.tabs.rememberFeatures
import com.linroid.ketch.app.ui.list.TaskCommand
import com.linroid.ketch.torrent.api.TorrentStall
import com.linroid.ketch.torrent.api.TorrentStallReason
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.inspector_stall_choked
import ketch.app.shared.generated.resources.inspector_stall_missing
import ketch.app.shared.generated.resources.inspector_stall_no_peers
import ketch.app.shared.generated.resources.inspector_stall_skip
import ketch.app.shared.generated.resources.inspector_stall_slow
import ketch.app.shared.generated.resources.inspector_stall_unknown

private val log = KetchLogger("Stall")

/**
 * Why a downloading torrent makes no progress, on a device whose torrent controls report it
 * ([TorrentStall]): "No peer or web seed has 2 pieces it needs" with Skip file when the files
 * those pieces belong to can be left out, so the rest finishes; "No peers for 3m 10s"; "Peers
 * connected, but none is sharing yet"; "Peers are slow". Nothing while it makes progress.
 *
 * The task is followed through one `TorrentInterest` subscription while this shows.
 */
@Composable
internal fun StallNotice(state: AppState, row: TaskRow, pending: Set<Pair<TaskKey, String>>) {
  if (row.state !is DownloadState.Downloading || !row.isTorrent) return
  val features = rememberFeatures(state, row)
  val controller = remember(features, row.key) { state.torrentControllerOf(row.key.deviceId) }
    ?: return
  var stall by remember(row.key, controller) { mutableStateOf<TorrentStall?>(null) }
  LaunchedEffect(row.key, controller) {
    val snapshots = state.torrentSnapshotsOf(row.key) ?: return@LaunchedEffect
    catchingUnlessCancelled { snapshots.collect { stall = it?.stall } }
      .onFailure { e ->
        log.d { "Stopped following taskId=${row.key.taskId}: ${e.describeCauses()}" }
      }
  }
  val current = stall ?: return
  val skip = if (filesSelectable(row, features)) filesAfterSkipping(row.request, current) else null
  val busy = pending.any { (key, label) -> key == row.key && label == TaskCommand.ChooseFiles.key }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
    modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
      text = stallMessage(current).resolve(),
      style = KetchTheme.typography.caption,
      color = KetchTheme.colors.textSecondary,
      modifier = Modifier.weight(1f),
    )
    if (skip != null) {
      KetchButton(
        text = Res.plurals.inspector_stall_skip.text(current.missingFileIds.size).resolve(),
        onClick = {
          state.runTaskCommand(row.task, TaskCommand.ChooseFiles.key,
            failure = { device -> TaskCommand.ChooseFiles.failure(verbatim(row.name), device) },
          ) { selectFiles(skip) }
        },
        variant = KetchButtonVariant.Secondary,
        size = KetchButtonSize.Small,
        loading = busy,
      )
    }
  }
}

/** What [StallNotice] says of [stall]. */
internal fun stallMessage(stall: TorrentStall): UiText = when (stall.reason) {
  TorrentStallReason.MISSING_PIECES ->
    Res.plurals.inspector_stall_missing.text(stall.missingPieces)
  TorrentStallReason.NO_PEERS -> Res.string.inspector_stall_no_peers.text(etaText(stall.seconds))
  TorrentStallReason.CHOKED -> Res.string.inspector_stall_choked.text()
  TorrentStallReason.SLOW_PEERS -> Res.string.inspector_stall_slow.text()
  TorrentStallReason.UNKNOWN -> Res.string.inspector_stall_unknown.text(etaText(stall.seconds))
}

/**
 * The files [request] downloads once the ones [stall] names are skipped; `null` when nothing
 * would be skipped or nothing would be left. A task that downloads every file starts from its
 * list.
 */
internal fun filesAfterSkipping(request: DownloadRequest, stall: TorrentStall): Set<String>? {
  if (stall.reason != TorrentStallReason.MISSING_PIECES || stall.missingFileIds.isEmpty()) {
    return null
  }
  val chosen = request.selectedFileIds.ifEmpty {
    request.resolvedSource?.files?.mapTo(HashSet()) { it.id } ?: return null
  }
  val kept = chosen - stall.missingFileIds.toSet()
  return kept.takeIf { it.isNotEmpty() && it.size < chosen.size }
}
