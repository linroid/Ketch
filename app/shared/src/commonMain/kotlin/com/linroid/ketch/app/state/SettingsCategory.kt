package com.linroid.ketch.app.state

import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.EmbeddedInstance
import com.linroid.ketch.app.instance.InstanceEntry
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_category_about
import ketch.app.shared.generated.resources.settings_category_about_description
import ketch.app.shared.generated.resources.settings_category_bittorrent
import ketch.app.shared.generated.resources.settings_category_bittorrent_description
import ketch.app.shared.generated.resources.settings_category_discover
import ketch.app.shared.generated.resources.settings_category_discover_description
import ketch.app.shared.generated.resources.settings_category_downloads
import ketch.app.shared.generated.resources.settings_category_downloads_description
import ketch.app.shared.generated.resources.settings_category_general
import ketch.app.shared.generated.resources.settings_category_general_description
import ketch.app.shared.generated.resources.settings_category_integration
import ketch.app.shared.generated.resources.settings_category_integration_description
import ketch.app.shared.generated.resources.settings_category_network
import ketch.app.shared.generated.resources.settings_category_network_description
import ketch.app.shared.generated.resources.settings_category_notifications
import ketch.app.shared.generated.resources.settings_category_notifications_description
import ketch.app.shared.generated.resources.settings_category_sharing
import ketch.app.shared.generated.resources.settings_category_sharing_description
import ketch.app.shared.generated.resources.settings_category_speed
import ketch.app.shared.generated.resources.settings_category_speed_description
import ketch.app.shared.generated.resources.settings_section_app
import ketch.app.shared.generated.resources.settings_section_device
import org.jetbrains.compose.resources.StringResource

/** The two groups of Settings: the app's own pages, then the pages of one device. */
enum class SettingsSection(private val titleResource: StringResource) {
  /** Pages about this app on this screen, whichever device it shows. */
  App(Res.string.settings_section_app),

  /** Pages about the device chosen in Settings, which may differ from the one the app shows. */
  Device(Res.string.settings_section_device);

  /** Name shown over the group. */
  val title: UiText get() = titleResource.text()
}

/**
 * Pages of Settings, in the order they are listed: the app's pages, then the device's.
 *
 * @property icon glyph of the page's tile.
 * @property page the page as [SettingsTarget] names it.
 */
enum class SettingsCategory(
  private val titleResource: StringResource,
  private val descriptionResource: StringResource,
  val icon: KetchIcon,
  val page: SettingsTarget.Page,
) {
  General(
    titleResource = Res.string.settings_category_general,
    descriptionResource = Res.string.settings_category_general_description,
    icon = KetchIcon.Settings,
    page = SettingsTarget.Page.General,
  ),
  Notifications(
    titleResource = Res.string.settings_category_notifications,
    descriptionResource = Res.string.settings_category_notifications_description,
    icon = KetchIcon.Bell,
    page = SettingsTarget.Page.Notifications,
  ),
  Integration(
    titleResource = Res.string.settings_category_integration,
    descriptionResource = Res.string.settings_category_integration_description,
    icon = KetchIcon.Browser,
    page = SettingsTarget.Page.Integration,
  ),
  Discover(
    titleResource = Res.string.settings_category_discover,
    descriptionResource = Res.string.settings_category_discover_description,
    icon = KetchIcon.Discover,
    page = SettingsTarget.Page.Discover,
  ),
  About(
    titleResource = Res.string.settings_category_about,
    descriptionResource = Res.string.settings_category_about_description,
    icon = KetchIcon.Info,
    page = SettingsTarget.Page.About,
  ),
  Downloads(
    titleResource = Res.string.settings_category_downloads,
    descriptionResource = Res.string.settings_category_downloads_description,
    icon = KetchIcon.Folder,
    page = SettingsTarget.Page.Downloads,
  ),
  Speed(
    titleResource = Res.string.settings_category_speed,
    descriptionResource = Res.string.settings_category_speed_description,
    icon = KetchIcon.Speed,
    page = SettingsTarget.Page.Speed,
  ),
  Network(
    titleResource = Res.string.settings_category_network,
    descriptionResource = Res.string.settings_category_network_description,
    icon = KetchIcon.Network,
    page = SettingsTarget.Page.Network,
  ),
  BitTorrent(
    titleResource = Res.string.settings_category_bittorrent,
    descriptionResource = Res.string.settings_category_bittorrent_description,
    icon = KetchIcon.FileTorrent,
    page = SettingsTarget.Page.BitTorrent,
  ),
  Sharing(
    titleResource = Res.string.settings_category_sharing,
    descriptionResource = Res.string.settings_category_sharing_description,
    icon = KetchIcon.Server,
    page = SettingsTarget.Page.Sharing,
  );

  /** Name shown in the list and the page header. */
  val titleText: UiText get() = titleResource.text()

  /** What the page holds, for the phone's list and for search. */
  val descriptionText: UiText get() = descriptionResource.text()

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
