package com.linroid.ketch.app.state

import com.linroid.ketch.app.icons.KetchIcon

/** Top-level areas of the app shell. */
enum class AppDestination(val label: String, val icon: KetchIcon) {
  Downloads("Downloads", KetchIcon.Active),
  Discover("Discover", KetchIcon.Ai),
  Settings("Settings", KetchIcon.Settings);

  companion object {
    /**
     * Destinations to offer in the navigation.
     *
     * Discover stays hidden until discovery can actually run, so the
     * app never shows a tab that only leads to a dead end. Settings is
     * always there — that is where discovery gets switched on.
     *
     * @param aiAvailable whether AI discovery is configured and usable.
     */
    fun visible(aiAvailable: Boolean): List<AppDestination> =
      entries.filter { it != Discover || aiAvailable }
  }
}
