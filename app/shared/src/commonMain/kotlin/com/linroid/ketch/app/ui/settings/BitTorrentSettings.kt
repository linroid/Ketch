package com.linroid.ketch.app.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.state.InstanceSettingsController
import com.linroid.ketch.app.state.MAX_EXTRA_TRACKERS
import com.linroid.ketch.app.state.parseTrackers
import com.linroid.ketch.app.state.trackersError

/**
 * Extra trackers of the embedded instance, applied to torrents as they
 * start or resume. A remote instance's trackers can only be changed on
 * that device, so it gets a note instead.
 *
 * @param instanceLabel name of the instance the settings belong to.
 */
@Composable
fun BitTorrentSettings(
  controller: InstanceSettingsController,
  instanceLabel: String,
) {
  val torrent = controller.torrent
  Column(verticalArrangement = Arrangement.spacedBy(24.dp)) {
    if (torrent == null) {
      SettingsNotice(
        text = "Extra trackers of $instanceLabel can only be changed on that device, in " +
          "Ketch's BitTorrent settings or under [torrent] in its config.toml.",
        tone = NoticeTone.Info,
      )
      return@Column
    }
    controller.torrentError?.let {
      SettingsNotice(text = it, tone = NoticeTone.Error)
    }
    SettingsGroup(
      title = "Extra trackers",
      footer = "Public torrents and magnet links announce to these as well as their own " +
        "trackers, which helps when a network blocks those. Private torrents never use " +
        "them. Torrents pick up changes when they start or resume.",
    ) {
      SettingsRow(
        title = "Trackers",
        description = "One announce URL per line, starting with http://, https:// or udp://.",
      ) {
        SettingsTextInput(
          value = torrent.trackers.joinToString("\n"),
          onCommit = { controller.updateTorrent(torrent.copy(trackers = parseTrackers(it))) },
          normalize = { parseTrackers(it).joinToString("\n") },
          validate = ::trackersError,
          placeholder = "udp://tracker.example.org:1337/announce\n" +
            "https://tracker.example.org/announce",
          mono = true,
          minLines = 5,
        )
      }
    }
    if (torrent.trackers.size > MAX_EXTRA_TRACKERS) {
      SettingsNotice(
        text = "Only the first $MAX_EXTRA_TRACKERS trackers are used.",
        tone = NoticeTone.Warning,
      )
    }
  }
}
