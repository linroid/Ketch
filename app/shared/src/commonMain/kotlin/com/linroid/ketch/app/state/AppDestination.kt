package com.linroid.ketch.app.state

import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.shell_destination_devices
import ketch.app.shared.generated.resources.shell_destination_discover
import ketch.app.shared.generated.resources.shell_destination_downloads

/**
 * Top-level pages of the app shell. Settings is not one of them: it opens as a window, a dialog
 * or a page over the current destination.
 *
 * @property label name shown in the navigation.
 * @property icon glyph shown in the navigation.
 * @property command shortcut command that shows the destination.
 */
enum class AppDestination(val label: UiText, val icon: KetchIcon, val command: KetchCommand) {
  /** The downloads of the active device; the home of every platform. */
  Downloads(
    Res.string.shell_destination_downloads.text(),
    KetchIcon.Active,
    KetchCommands.tab(StatusFilter.All)
  ),

  /** AI search for downloads. */
  Discover(
    Res.string.shell_destination_discover.text(),
    KetchIcon.Discover,
    KetchCommands.Discover
  ),

  /** Every device the app knows. */
  Devices(
    Res.string.shell_destination_devices.text(),
    KetchIcon.Devices,
    KetchCommands.Devices
  );

  companion object {
    /**
     * Destinations to offer in the navigation.
     *
     * Discover shows wherever discovery can run, set up or not: until it is set up, the page
     * shows how to set it up.
     *
     * @param aiSupported whether this platform can run AI discovery.
     */
    fun visible(aiSupported: Boolean): List<AppDestination> =
      entries.filter { it != Discover || aiSupported }
  }
}
