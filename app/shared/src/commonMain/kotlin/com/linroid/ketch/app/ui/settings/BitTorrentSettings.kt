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
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.InstanceEntry
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.MAX_EXTRA_TRACKERS
import com.linroid.ketch.app.state.RejectedTracker
import com.linroid.ketch.app.state.addTrackers
import com.linroid.ketch.app.state.trackerHost
import com.linroid.ketch.app.theme.KetchTheme

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
        text = "Extra trackers of ${device.label} can only be changed on that device, in " +
          "Ketch's BitTorrent settings or under [torrent] in its config.toml.",
        tone = NoticeTone.Info,
      )
      return@Column
    }
    val trackers = torrent.trackers
    val save = { list: List<String> -> controller.updateTorrent(torrent.copy(trackers = list)) }
    controller.torrentError?.let {
      SettingsNotice(text = it, tone = NoticeTone.Error)
    }
    if (removed.isNotEmpty()) {
      SettingsNotice(
        text = if (removed.size == 1) "Removed 1 tracker." else "Removed ${removed.size} trackers.",
        tone = NoticeTone.Info,
        action = {
          KetchButton(
            text = "Undo",
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
      title = "Extra trackers",
      footer = "Public torrents and magnet links announce to these as well as their own " +
        "trackers, which helps when a network blocks those. Private torrents never use " +
        "them. Torrents pick up changes when they start or resume." +
        if (trackers.size > MAX_EXTRA_TRACKERS) {
          " Only the first $MAX_EXTRA_TRACKERS are used."
        } else {
          ""
        },
      action = if (trackers.isNotEmpty()) {
        {
          KetchButton(
            text = "Remove all",
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
        SettingsRow(
          title = "No extra trackers",
          description = "Public torrents use only their own trackers.",
        )
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
                KetchBadge("Unused", KetchBadgeTone.Warning)
              }
              KetchIconButton(
                icon = KetchIcon.Close,
                onClick = { save(trackers - url) },
                size = KetchButtonSize.Small,
                contentDescription = "Remove $host",
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
    title = "Add trackers",
    description = "Paste one or more announce URLs starting with http://, https:// or udp://.",
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
        error = rejectionMessage(rejected),
        onDone = add,
        mono = true,
      )
      KetchButton(
        text = "Add",
        onClick = add,
        variant = KetchButtonVariant.Secondary,
        enabled = text.isNotBlank(),
      )
    }
  }
}

/** Explains why the URLs in [rejected] were not added, or `null` when none were. */
private fun rejectionMessage(rejected: List<RejectedTracker>): String? {
  if (rejected.isEmpty()) return null
  if (rejected.size == 1) return "Couldn't add ${rejected[0].url}: ${rejected[0].reason}"
  val listed = rejected.take(MAX_LISTED_REJECTIONS).joinToString("\n") { "${it.url}: ${it.reason}" }
  val more = rejected.size - MAX_LISTED_REJECTIONS
  return "Couldn't add ${rejected.size} URLs:\n$listed" + if (more > 0) "\nand $more more." else ""
}
