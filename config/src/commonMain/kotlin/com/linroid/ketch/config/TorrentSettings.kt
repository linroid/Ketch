package com.linroid.ketch.config

import kotlinx.serialization.Serializable

/**
 * BitTorrent settings for the local engine.
 *
 * @property trackers extra tracker announce URLs (`http`, `https` or `udp`)
 *   added to public torrents as a final fallback tier. Private torrents and
 *   tracker-only discovery ignore them.
 */
@Serializable
data class TorrentSettings(
  val trackers: List<String> = emptyList(),
)
