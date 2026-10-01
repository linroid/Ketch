package com.linroid.ketch.app.state

import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands

/**
 * Top-level pages of the app shell. Settings is not one of them: it opens as a window, a dialog
 * or a page over the current destination.
 *
 * @property label name shown in the navigation.
 * @property icon glyph shown in the navigation.
 * @property command shortcut command that shows the destination.
 */
enum class AppDestination(val label: String, val icon: KetchIcon, val command: KetchCommand) {
  /** The downloads of the active device; the home of every platform. */
  Downloads("Downloads", KetchIcon.Active, KetchCommands.tab(StatusFilter.All)),

  /** AI search for downloads. */
  Discover("Discover", KetchIcon.Discover, KetchCommands.Discover),

  /** Every device the app knows. */
  Devices("Devices", KetchIcon.Devices, KetchCommands.Devices);

  companion object {
    /**
     * Destinations to offer in the navigation.
     *
     * Discover stays hidden until discovery can actually run, so the app never shows a page
     * that only leads to a dead end; Settings is where discovery gets switched on.
     *
     * @param aiAvailable whether AI discovery is configured and usable.
     */
    fun visible(aiAvailable: Boolean): List<AppDestination> =
      entries.filter { it != Discover || aiAvailable }
  }
}
