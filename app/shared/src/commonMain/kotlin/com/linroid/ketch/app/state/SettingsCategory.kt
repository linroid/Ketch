package com.linroid.ketch.app.state

import com.linroid.ketch.app.icons.KetchIcon

/**
 * Sections of the Settings destination, in the order they are listed.
 *
 * @property title name shown in the category list and page header.
 * @property summary one-line hint under the title in the list.
 */
enum class SettingsCategory(
  val title: String,
  val summary: String,
  val icon: KetchIcon,
) {
  General("General", "Device name, theme and accent colour", KetchIcon.Settings),
  Downloads("Downloads", "Folder, queue and retries", KetchIcon.Active),
  Speed("Speed", "Speed modes, Slow lane and Auto rules", KetchIcon.Speed),
  Network("Network", "Network interfaces used for downloads", KetchIcon.Network),
  BitTorrent("BitTorrent", "Extra trackers for public torrents", KetchIcon.FileTorrent),
  RemoteAccess("Sharing", "Pair a phone or browser to control this device", KetchIcon.Server),
  Ai("AI discovery", "Model provider and web search", KetchIcon.Ai),
  About("About", "Version and project links", KetchIcon.Info);

  companion object {
    /**
     * Categories to offer on this platform.
     *
     * @param serverSupported whether this platform can run the local
     *   server; remote access is meaningless without it.
     */
    fun visible(serverSupported: Boolean): List<SettingsCategory> =
      entries.filter { it != RemoteAccess || serverSupported }
  }
}
