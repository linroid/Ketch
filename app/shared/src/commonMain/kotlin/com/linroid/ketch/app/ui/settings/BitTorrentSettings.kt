package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.linroid.ketch.app.components.KetchBadge
import com.linroid.ketch.app.components.KetchBadgeTone
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.MAX_EXTRA_TRACKERS
import com.linroid.ketch.app.state.RejectedTracker
import com.linroid.ketch.app.state.addTrackers
import com.linroid.ketch.app.state.trackerHost
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_add
import ketch.app.shared.generated.resources.action_undo
import ketch.app.shared.generated.resources.settings_torrent_add
import ketch.app.shared.generated.resources.settings_torrent_add_hint
import ketch.app.shared.generated.resources.settings_torrent_footer
import ketch.app.shared.generated.resources.settings_torrent_footer_first_only
import ketch.app.shared.generated.resources.settings_torrent_no_trackers
import ketch.app.shared.generated.resources.settings_torrent_rejected
import ketch.app.shared.generated.resources.settings_torrent_rejected_line
import ketch.app.shared.generated.resources.settings_torrent_rejected_many
import ketch.app.shared.generated.resources.settings_torrent_rejected_more
import ketch.app.shared.generated.resources.settings_torrent_remote
import ketch.app.shared.generated.resources.settings_torrent_remove
import ketch.app.shared.generated.resources.settings_torrent_remove_all
import ketch.app.shared.generated.resources.settings_torrent_removed
import ketch.app.shared.generated.resources.settings_torrent_trackers
import ketch.app.shared.generated.resources.settings_torrent_unused
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/** Rejected URLs listed under the add field; the rest are only counted. */
private const val MAX_LISTED_REJECTIONS = 3

/**
 * Extra trackers of [device], applied to torrents as they start or resume. Only the embedded
 * device's trackers can be changed here; a remote device gets a note instead.
 */
@Composable
fun BitTorrentSettings(state: AppState, device: InstanceEntry) {
  val controller = state.settingsFor(device)
  val torrent = controller.torrent
  // Trackers taken away by "Remove all", offered back until restored.
  var removed by remember { mutableStateOf(emptyList<String>()) }
  Column(verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.sectionGap)) {
    if (torrent == null) {
      SettingsNotice(
        text = stringResource(Res.string.settings_torrent_remote, device.label),
        tone = NoticeTone.Info,
      )
      return@Column
    }
    val trackers = torrent.trackers
    val save = { list: List<String> -> controller.updateTorrent(torrent.copy(trackers = list)) }
    controller.torrentError?.let {
      SettingsNotice(text = it.resolve(), tone = NoticeTone.Error)
    }
    if (removed.isNotEmpty()) {
      SettingsNotice(
        text = pluralStringResource(
          Res.plurals.settings_torrent_removed,
          removed.size,
          removed.size,
        ),
        tone = NoticeTone.Info,
        action = {
          KetchButton(
            text = stringResource(Res.string.action_undo),
            onClick = {
              save((removed + trackers).distinct())
              removed = emptyList()
            },
            variant = KetchButtonVariant.Secondary,
            size = KetchButtonSize.Small,
            leadingIcon = KetchIcon.Undo,
          )
        },
      )
    }
    SettingsGroup(
      title = stringResource(Res.string.settings_torrent_trackers),
      footer = if (trackers.size > MAX_EXTRA_TRACKERS) {
        stringResource(Res.string.settings_torrent_footer_first_only, MAX_EXTRA_TRACKERS)
      } else {
        stringResource(Res.string.settings_torrent_footer)
      },
      action = if (trackers.isNotEmpty()) {
        {
          KetchButton(
            text = stringResource(Res.string.settings_torrent_remove_all),
            onClick = {
              removed = trackers
              save(emptyList())
            },
            variant = KetchButtonVariant.Ghost,
            size = KetchButtonSize.Small,
          )
        }
      } else {
        null
      },
    ) {
      AddTrackersRow(
        onAdd = { text ->
          val added = addTrackers(trackers, text)
          if (added.trackers != trackers) save(added.trackers)
          added.rejected
        },
      )
      if (trackers.isEmpty()) {
        SettingsRow(title = stringResource(Res.string.settings_torrent_no_trackers))
      }
      trackers.forEachIndexed { index, url ->
        val host = trackerHost(url)
        SettingsRow(
          title = host,
          description = url,
          trailing = {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
            ) {
              if (index >= MAX_EXTRA_TRACKERS) {
                KetchBadge(
                  text = stringResource(Res.string.settings_torrent_unused),
                  tone = KetchBadgeTone.Warning,
                )
              }
              KetchIconButton(
                icon = KetchIcon.Close,
                onClick = { save(trackers - url) },
                size = KetchButtonSize.Small,
                contentDescription = stringResource(Res.string.settings_torrent_remove, host),
              )
            }
          },
        )
      }
    }
  }
}

/**
 * Field for typing or pasting tracker URLs. [onAdd] adds them and returns
 * the ones it rejected, which stay in the field with the reasons.
 */
@Composable
private fun AddTrackersRow(onAdd: (String) -> List<RejectedTracker>) {
  var text by remember { mutableStateOf("") }
  var rejected by remember { mutableStateOf(emptyList<RejectedTracker>()) }
  val add = {
    if (text.isNotBlank()) {
      rejected = onAdd(text)
      text = rejected.joinToString(" ") { it.url }
    }
  }
  SettingsRow(
    title = stringResource(Res.string.settings_torrent_add),
    description = stringResource(Res.string.settings_torrent_add_hint),
  ) {
    Row(
      verticalAlignment = Alignment.Top,
      horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s2),
    ) {
      SettingsTextField(
        value = text,
        onValueChange = {
          text = it
          rejected = emptyList()
        },
        modifier = Modifier.weight(1f),
        placeholder = "udp://tracker.example.org:1337/announce",
        error = rejectionMessage(rejected)?.resolve(),
        onDone = add,
        mono = true,
      )
      KetchButton(
        text = stringResource(Res.string.action_add),
        onClick = add,
        variant = KetchButtonVariant.Secondary,
        enabled = text.isNotBlank(),
      )
    }
  }
}

/** Explains why the URLs in [rejected] were not added, or `null` when none were. */
private fun rejectionMessage(rejected: List<RejectedTracker>): UiText? {
  if (rejected.isEmpty()) return null
  if (rejected.size == 1) {
    return Res.string.settings_torrent_rejected.text(rejected[0].url, rejected[0].problem)
  }
  val listed = rejected.take(MAX_LISTED_REJECTIONS).map {
    Res.string.settings_torrent_rejected_line.text(it.url, it.problem)
  }
  val more = rejected.size - MAX_LISTED_REJECTIONS
  val lines = listOf(Res.plurals.settings_torrent_rejected_many.text(rejected.size)) + listed +
    listOfNotNull(Res.plurals.settings_torrent_rejected_more.text(more).takeIf { more > 0 })
  return lines.joinText("\n")
}
