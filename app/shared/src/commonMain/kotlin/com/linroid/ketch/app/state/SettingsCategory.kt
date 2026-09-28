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
  Downloads("Downloads", "Location, queue, speed and retries", KetchIcon.Active),
  Network("Network", "Network interfaces used for downloads", KetchIcon.Network),
  RemoteAccess("Remote access", "Control this device from other devices", KetchIcon.Server),
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
