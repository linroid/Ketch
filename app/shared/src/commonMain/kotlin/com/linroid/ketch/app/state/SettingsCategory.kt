package com.linroid.ketch.app.state

import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry

/** The two groups of Settings: the app's own pages, then the pages of one device. */
enum class SettingsSection(val title: String) {
  /** Pages about this app on this screen, whichever device it shows. */
  App("This app"),

  /** Pages about the device chosen in Settings, which may differ from the one the app shows. */
  Device("Device"),
}

/**
 * Pages of Settings, in the order they are listed: the app's pages, then the device's.
 *
 * @property title name shown in the list and the page header.
 * @property description what the page holds, for the phone's list and for search.
 * @property icon glyph of the page's tile.
 * @property page the page as [SettingsTarget] names it.
 */
enum class SettingsCategory(
  val title: String,
  val description: String,
  val icon: KetchIcon,
  val page: SettingsTarget.Page,
) {
  General(
    title = "General",
    description = "Appearance, startup and the name of this device",
    icon = KetchIcon.Settings,
    page = SettingsTarget.Page.General,
  ),
  Notifications(
    title = "Notifications",
    description = "What Ketch tells you about, and how",
    icon = KetchIcon.Bell,
    page = SettingsTarget.Page.Notifications,
  ),
  Integration(
    title = "Integration",
    description = "Browser extension, magnet links and the clipboard",
    icon = KetchIcon.Browser,
    page = SettingsTarget.Page.Integration,
  ),
  Discover(
    title = "Discover",
    description = "The AI model and web search that find downloads",
    icon = KetchIcon.Discover,
    page = SettingsTarget.Page.Discover,
  ),
  About(
    title = "About",
    description = "Version, licenses and logs",
    icon = KetchIcon.Info,
    page = SettingsTarget.Page.About,
  ),
  Downloads(
    title = "Downloads",
    description = "Where downloads go, the queue and retries",
    icon = KetchIcon.Folder,
    page = SettingsTarget.Page.Downloads,
  ),
  Speed(
    title = "Speed",
    description = "Speed modes, Slow lane and Auto rules",
    icon = KetchIcon.Speed,
    page = SettingsTarget.Page.Speed,
  ),
  Network(
    title = "Network",
    description = "The networks downloads are spread across",
    icon = KetchIcon.Network,
    page = SettingsTarget.Page.Network,
  ),
  BitTorrent(
    title = "BitTorrent",
    description = "Extra trackers for public torrents",
    icon = KetchIcon.FileTorrent,
    page = SettingsTarget.Page.BitTorrent,
  ),
  Sharing(
    title = "Sharing",
    description = "Pair a phone or browser to control this device",
    icon = KetchIcon.Server,
    page = SettingsTarget.Page.Sharing,
  );

  /** Group the page is listed in. */
  val section: SettingsSection
    get() = if (page.isDevicePage) SettingsSection.Device else SettingsSection.App

  companion object {
    /** The category showing [page]. */
    fun of(page: SettingsTarget.Page): SettingsCategory = entries.first { it.page == page }

    /**
     * Pages to offer.
     *
     * @param discoverSupported whether this app can run AI discovery; without it the Discover
     *   page is left out and About says where discovery runs.
     * @param device device whose pages are shown, or `null` while none is connected, which
     *   leaves out every device page.
     * @param serverSupported whether this app can share its own device; Sharing is offered only
     *   then, and only for that device.
     */
    fun visible(
      discoverSupported: Boolean,
      device: InstanceEntry?,
      serverSupported: Boolean,
    ): List<SettingsCategory> {
      val embedded = device is EmbeddedInstance
      return entries.filter { category ->
        when (category) {
          Discover -> discoverSupported
          BitTorrent -> embedded
          Sharing -> embedded && serverSupported
          else -> !category.page.isDevicePage || device != null
        }
      }
    }
  }
}
