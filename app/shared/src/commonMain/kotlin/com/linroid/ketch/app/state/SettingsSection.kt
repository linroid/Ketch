package com.linroid.ketch.app.state

import com.linroid.ketch.app.icons.KetchIcon

/** Sections of the Settings destination, in display order. */
enum class SettingsSection(val label: String, val icon: KetchIcon) {
  General("General", KetchIcon.Local),
  Appearance("Appearance", KetchIcon.Appearance),
  Downloads("Downloads", KetchIcon.Active),
  Server("Server", KetchIcon.Server),
  Ai("AI discovery", KetchIcon.Ai);

  companion object {
    /**
     * Sections to offer on this platform.
     *
     * @param serverSupported whether this platform can run a local server.
     */
    fun visible(serverSupported: Boolean): List<SettingsSection> =
      entries.filter { it != Server || serverSupported }
  }
}
