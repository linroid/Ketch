package com.linroid.ketch.config

import kotlinx.serialization.Serializable

/**
 * BitTorrent settings for the local engine.
 *
 * @property trackers extra tracker announce URLs (`http`, `https` or `udp`)
 *   that public torrents announce to alongside their own trackers. Private
 *   torrents and tracker-only discovery ignore them.
 */
@Serializable
data class TorrentSettings(
  val trackers: List<String> = emptyList(),
)
