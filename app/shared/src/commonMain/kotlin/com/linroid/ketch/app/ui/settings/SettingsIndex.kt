package com.linroid.ketch.app.ui.settings

import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.state.SettingsCategory
import com.linroid.ketch.app.theme.KetchAccent
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.settings_about_checklist
import ketch.app.shared.generated.resources.settings_about_licenses
import ketch.app.shared.generated.resources.settings_about_report
import ketch.app.shared.generated.resources.settings_about_source
import ketch.app.shared.generated.resources.settings_about_version
import ketch.app.shared.generated.resources.settings_about_welcome
import ketch.app.shared.generated.resources.settings_ai_access_mode
import ketch.app.shared.generated.resources.settings_ai_content_filter
import ketch.app.shared.generated.resources.settings_ai_access_trusted
import ketch.app.shared.generated.resources.settings_ai_api_key
import ketch.app.shared.generated.resources.settings_ai_discovery
import ketch.app.shared.generated.resources.settings_ai_endpoint
import ketch.app.shared.generated.resources.settings_ai_endpoint_optional
import ketch.app.shared.generated.resources.settings_ai_model
import ketch.app.shared.generated.resources.settings_ai_provider
import ketch.app.shared.generated.resources.settings_ai_test
import ketch.app.shared.generated.resources.settings_ai_web_search
import ketch.app.shared.generated.resources.settings_downloads_folders
import ketch.app.shared.generated.resources.settings_downloads_per_server
import ketch.app.shared.generated.resources.settings_downloads_retries
import ketch.app.shared.generated.resources.settings_downloads_run_at_once
import ketch.app.shared.generated.resources.settings_downloads_save_to_row
import ketch.app.shared.generated.resources.settings_general_accent
import ketch.app.shared.generated.resources.settings_general_badge
import ketch.app.shared.generated.resources.settings_general_close
import ketch.app.shared.generated.resources.settings_general_density
import ketch.app.shared.generated.resources.settings_general_device_name
import ketch.app.shared.generated.resources.settings_general_keep_awake
import ketch.app.shared.generated.resources.settings_general_language
import ketch.app.shared.generated.resources.settings_general_open_at_login
import ketch.app.shared.generated.resources.settings_general_reduce_motion
import ketch.app.shared.generated.resources.settings_general_shortcuts
import ketch.app.shared.generated.resources.settings_general_start_hidden
import ketch.app.shared.generated.resources.settings_general_theme
import ketch.app.shared.generated.resources.settings_integration_clipboard_suggest
import ketch.app.shared.generated.resources.settings_integration_extension
import ketch.app.shared.generated.resources.settings_integration_magnet
import ketch.app.shared.generated.resources.settings_integration_quick_add
import ketch.app.shared.generated.resources.settings_integration_torrent
import ketch.app.shared.generated.resources.settings_logs_open
import ketch.app.shared.generated.resources.settings_logs_share
import ketch.app.shared.generated.resources.settings_network_networks
import ketch.app.shared.generated.resources.settings_network_spread
import ketch.app.shared.generated.resources.settings_notifications_all_finished
import ketch.app.shared.generated.resources.settings_notifications_background
import ketch.app.shared.generated.resources.settings_notifications_browser
import ketch.app.shared.generated.resources.settings_notifications_devices
import ketch.app.shared.generated.resources.settings_notifications_failed
import ketch.app.shared.generated.resources.settings_notifications_finished
import ketch.app.shared.generated.resources.settings_notifications_offline
import ketch.app.shared.generated.resources.settings_search_accent
import ketch.app.shared.generated.resources.settings_search_accent_keywords
import ketch.app.shared.generated.resources.settings_search_ai_access
import ketch.app.shared.generated.resources.settings_search_ai_access_keywords
import ketch.app.shared.generated.resources.settings_search_ai_content_filter
import ketch.app.shared.generated.resources.settings_search_ai_content_filter_keywords
import ketch.app.shared.generated.resources.settings_search_ai_keywords
import ketch.app.shared.generated.resources.settings_search_ai_trusted
import ketch.app.shared.generated.resources.settings_search_ai_trusted_keywords
import ketch.app.shared.generated.resources.settings_search_all_finished_keywords
import ketch.app.shared.generated.resources.settings_search_api_key_keywords
import ketch.app.shared.generated.resources.settings_search_auto_start_keywords
import ketch.app.shared.generated.resources.settings_search_background_keywords
import ketch.app.shared.generated.resources.settings_search_badge
import ketch.app.shared.generated.resources.settings_search_badge_keywords
import ketch.app.shared.generated.resources.settings_search_browser_notifications
import ketch.app.shared.generated.resources.settings_search_browser_notifications_keywords
import ketch.app.shared.generated.resources.settings_search_checklist_keywords
import ketch.app.shared.generated.resources.settings_search_clipboard_keywords
import ketch.app.shared.generated.resources.settings_search_close
import ketch.app.shared.generated.resources.settings_search_close_keywords
import ketch.app.shared.generated.resources.settings_search_keep_awake_keywords
import ketch.app.shared.generated.resources.settings_search_code_keywords
import ketch.app.shared.generated.resources.settings_search_connections_keywords
import ketch.app.shared.generated.resources.settings_search_density
import ketch.app.shared.generated.resources.settings_search_density_keywords
import ketch.app.shared.generated.resources.settings_search_device_name
import ketch.app.shared.generated.resources.settings_search_device_name_keywords
import ketch.app.shared.generated.resources.settings_search_devices_keywords
import ketch.app.shared.generated.resources.settings_search_discoverable_keywords
import ketch.app.shared.generated.resources.settings_search_endpoint_keywords
import ketch.app.shared.generated.resources.settings_search_extension
import ketch.app.shared.generated.resources.settings_search_extension_keywords
import ketch.app.shared.generated.resources.settings_search_failed_keywords
import ketch.app.shared.generated.resources.settings_search_finished_keywords
import ketch.app.shared.generated.resources.settings_search_folders
import ketch.app.shared.generated.resources.settings_search_folders_keywords
import ketch.app.shared.generated.resources.settings_search_full_speed_cap
import ketch.app.shared.generated.resources.settings_search_full_speed_cap_keywords
import ketch.app.shared.generated.resources.settings_search_language_keywords
import ketch.app.shared.generated.resources.settings_search_licenses_keywords
import ketch.app.shared.generated.resources.settings_search_logs
import ketch.app.shared.generated.resources.settings_search_logs_description
import ketch.app.shared.generated.resources.settings_search_logs_keywords
import ketch.app.shared.generated.resources.settings_search_magnet_keywords
import ketch.app.shared.generated.resources.settings_search_model_keywords
import ketch.app.shared.generated.resources.settings_search_networks
import ketch.app.shared.generated.resources.settings_search_networks_keywords
import ketch.app.shared.generated.resources.settings_search_offline_keywords
import ketch.app.shared.generated.resources.settings_search_open_at_login_keywords
import ketch.app.shared.generated.resources.settings_search_pair
import ketch.app.shared.generated.resources.settings_search_pair_keywords
import ketch.app.shared.generated.resources.settings_search_per_server
import ketch.app.shared.generated.resources.settings_search_per_server_keywords
import ketch.app.shared.generated.resources.settings_search_port_keywords
import ketch.app.shared.generated.resources.settings_search_provider
import ketch.app.shared.generated.resources.settings_search_provider_keywords
import ketch.app.shared.generated.resources.settings_search_quick_add_keywords
import ketch.app.shared.generated.resources.settings_search_reachable_keywords
import ketch.app.shared.generated.resources.settings_search_reduce_motion
import ketch.app.shared.generated.resources.settings_search_reduce_motion_keywords
import ketch.app.shared.generated.resources.settings_search_report_keywords
import ketch.app.shared.generated.resources.settings_search_retries_keywords
import ketch.app.shared.generated.resources.settings_search_rules
import ketch.app.shared.generated.resources.settings_search_rules_keywords
import ketch.app.shared.generated.resources.settings_search_run_at_once
import ketch.app.shared.generated.resources.settings_search_run_at_once_keywords
import ketch.app.shared.generated.resources.settings_search_save_to_keywords
import ketch.app.shared.generated.resources.settings_search_shortcuts
import ketch.app.shared.generated.resources.settings_search_shortcuts_keywords
import ketch.app.shared.generated.resources.settings_search_slow_lane_keywords
import ketch.app.shared.generated.resources.settings_search_source_keywords
import ketch.app.shared.generated.resources.settings_search_speed_mode
import ketch.app.shared.generated.resources.settings_search_speed_mode_keywords
import ketch.app.shared.generated.resources.settings_search_start_hidden_keywords
import ketch.app.shared.generated.resources.settings_search_test_keywords
import ketch.app.shared.generated.resources.settings_search_theme
import ketch.app.shared.generated.resources.settings_search_theme_keywords
import ketch.app.shared.generated.resources.settings_search_torrent_keywords
import ketch.app.shared.generated.resources.settings_search_trackers
import ketch.app.shared.generated.resources.settings_search_trackers_keywords
import ketch.app.shared.generated.resources.settings_search_version_keywords
import ketch.app.shared.generated.resources.settings_search_web_search
import ketch.app.shared.generated.resources.settings_search_web_search_keywords
import ketch.app.shared.generated.resources.settings_search_websites_keywords
import ketch.app.shared.generated.resources.settings_search_welcome_keywords
import ketch.app.shared.generated.resources.settings_sharing_access_code
import ketch.app.shared.generated.resources.settings_sharing_auto_start
import ketch.app.shared.generated.resources.settings_sharing_discoverable
import ketch.app.shared.generated.resources.settings_sharing_pair
import ketch.app.shared.generated.resources.settings_sharing_port
import ketch.app.shared.generated.resources.settings_sharing_reachable
import ketch.app.shared.generated.resources.settings_sharing_websites
import ketch.app.shared.generated.resources.settings_speed_connections
import ketch.app.shared.generated.resources.settings_speed_full_cap
import ketch.app.shared.generated.resources.settings_speed_limit
import ketch.app.shared.generated.resources.settings_speed_mode
import ketch.app.shared.generated.resources.settings_speed_rules
import ketch.app.shared.generated.resources.settings_speed_slow_lane
import ketch.app.shared.generated.resources.settings_torrent_trackers

/**
 * Every setting search finds, page by page. Titles and anchors are the resources of the rows and
 * groups on the pages, so a search result reads as the page does and can scroll to them.
 */
internal val SettingsIndex: List<SettingsIndexEntry> = listOf(
  // General
  SettingsIndexEntry(
    category = SettingsCategory.General,
    title = Res.string.settings_general_device_name,
    description = Res.string.settings_search_device_name.text(),
    keywords = Res.string.settings_search_device_name_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.General,
    title = Res.string.settings_general_theme,
    description = Res.string.settings_search_theme.text(),
    keywords = Res.string.settings_search_theme_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.General,
    title = Res.string.settings_general_accent,
    description = Res.string.settings_search_accent.text(
      KetchAccent.Signal.displayName,
      KetchAccent.Harbor.displayName,
      KetchAccent.Fathom.displayName,
      KetchAccent.Beacon.displayName,
    ),
    keywords = Res.string.settings_search_accent_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.General,
    title = Res.string.settings_general_density,
    description = Res.string.settings_search_density.text(),
    keywords = Res.string.settings_search_density_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.General,
    title = Res.string.settings_general_reduce_motion,
    description = Res.string.settings_search_reduce_motion.text(),
    keywords = Res.string.settings_search_reduce_motion_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.General,
    title = Res.string.settings_general_language,
    keywords = Res.string.settings_search_language_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.General,
    title = Res.string.settings_general_close,
    description = Res.string.settings_search_close.text(),
    keywords = Res.string.settings_search_close_keywords,
    needs = SettingsFeature.Desktop,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.General,
    title = Res.string.settings_general_open_at_login,
    keywords = Res.string.settings_search_open_at_login_keywords,
    needs = SettingsFeature.Desktop,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.General,
    title = Res.string.settings_general_start_hidden,
    keywords = Res.string.settings_search_start_hidden_keywords,
    needs = SettingsFeature.Desktop,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.General,
    title = Res.string.settings_general_badge,
    description = Res.string.settings_search_badge.text(),
    keywords = Res.string.settings_search_badge_keywords,
    needs = SettingsFeature.Desktop,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.General,
    title = Res.string.settings_general_keep_awake,
    keywords = Res.string.settings_search_keep_awake_keywords,
    needs = SettingsFeature.KeepAwake,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.General,
    title = Res.string.settings_general_shortcuts,
    description = Res.string.settings_search_shortcuts.text(),
    keywords = Res.string.settings_search_shortcuts_keywords,
    needs = SettingsFeature.Keyboard,
  ),
  // Notifications
  SettingsIndexEntry(
    category = SettingsCategory.Notifications,
    title = Res.string.settings_notifications_finished,
    keywords = Res.string.settings_search_finished_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Notifications,
    title = Res.string.settings_notifications_failed,
    keywords = Res.string.settings_search_failed_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Notifications,
    title = Res.string.settings_notifications_all_finished,
    keywords = Res.string.settings_search_all_finished_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Notifications,
    title = Res.string.settings_notifications_offline,
    keywords = Res.string.settings_search_offline_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Notifications,
    title = Res.string.settings_notifications_background,
    keywords = Res.string.settings_search_background_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Notifications,
    title = Res.string.settings_notifications_devices,
    keywords = Res.string.settings_search_devices_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Notifications,
    title = Res.string.settings_notifications_browser,
    description = Res.string.settings_search_browser_notifications.text(),
    keywords = Res.string.settings_search_browser_notifications_keywords,
    needs = SettingsFeature.BrowserNotifications,
  ),
  // Integration
  SettingsIndexEntry(
    category = SettingsCategory.Integration,
    title = Res.string.settings_integration_extension,
    description = Res.string.settings_search_extension.text(),
    keywords = Res.string.settings_search_extension_keywords,
    needs = SettingsFeature.Desktop,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Integration,
    title = Res.string.settings_integration_magnet,
    keywords = Res.string.settings_search_magnet_keywords,
    needs = SettingsFeature.Desktop,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Integration,
    title = Res.string.settings_integration_torrent,
    keywords = Res.string.settings_search_torrent_keywords,
    needs = SettingsFeature.Desktop,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Integration,
    title = Res.string.settings_integration_clipboard_suggest,
    keywords = Res.string.settings_search_clipboard_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Integration,
    title = Res.string.settings_integration_quick_add,
    keywords = Res.string.settings_search_quick_add_keywords,
  ),
  // Discover
  SettingsIndexEntry(
    category = SettingsCategory.Discover,
    title = Res.string.settings_ai_discovery,
    keywords = Res.string.settings_search_ai_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Discover,
    title = Res.string.settings_ai_content_filter,
    description = Res.string.settings_search_ai_content_filter.text(),
    keywords = Res.string.settings_search_ai_content_filter_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Discover,
    title = Res.string.settings_ai_access_mode,
    description = Res.string.settings_search_ai_access.text(),
    keywords = Res.string.settings_search_ai_access_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Discover,
    title = Res.string.settings_ai_access_trusted,
    description = Res.string.settings_search_ai_trusted.text(),
    keywords = Res.string.settings_search_ai_trusted_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Discover,
    title = Res.string.settings_ai_provider,
    description = Res.string.settings_search_provider.text(),
    keywords = Res.string.settings_search_provider_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Discover,
    title = Res.string.settings_ai_api_key,
    keywords = Res.string.settings_search_api_key_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Discover,
    title = Res.string.settings_ai_model,
    keywords = Res.string.settings_search_model_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Discover,
    title = Res.string.settings_ai_endpoint,
    keywords = Res.string.settings_search_endpoint_keywords,
    anchors = listOf(Res.string.settings_ai_endpoint, Res.string.settings_ai_endpoint_optional),
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Discover,
    title = Res.string.settings_ai_test,
    keywords = Res.string.settings_search_test_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Discover,
    title = Res.string.settings_ai_web_search,
    description = Res.string.settings_search_web_search.text(),
    keywords = Res.string.settings_search_web_search_keywords,
  ),
  // About
  SettingsIndexEntry(
    category = SettingsCategory.About,
    title = Res.string.settings_about_version,
    keywords = Res.string.settings_search_version_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.About,
    title = Res.string.settings_about_source,
    keywords = Res.string.settings_search_source_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.About,
    title = Res.string.settings_about_report,
    keywords = Res.string.settings_search_report_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.About,
    title = Res.string.settings_about_licenses,
    keywords = Res.string.settings_search_licenses_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.About,
    title = Res.string.settings_search_logs,
    description = Res.string.settings_search_logs_description.text(),
    keywords = Res.string.settings_search_logs_keywords,
    anchors = listOf(Res.string.settings_logs_open, Res.string.settings_logs_share),
    needs = SettingsFeature.Logs,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.About,
    title = Res.string.settings_about_checklist,
    keywords = Res.string.settings_search_checklist_keywords,
    needs = SettingsFeature.SetupChecklist,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.About,
    title = Res.string.settings_about_welcome,
    keywords = Res.string.settings_search_welcome_keywords,
    needs = SettingsFeature.Mobile,
  ),
  // Downloads
  SettingsIndexEntry(
    category = SettingsCategory.Downloads,
    title = Res.string.settings_downloads_save_to_row,
    keywords = Res.string.settings_search_save_to_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Downloads,
    title = Res.string.settings_downloads_folders,
    description = Res.string.settings_search_folders.text(),
    keywords = Res.string.settings_search_folders_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Downloads,
    title = Res.string.settings_downloads_run_at_once,
    description = Res.string.settings_search_run_at_once.text(),
    keywords = Res.string.settings_search_run_at_once_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Downloads,
    title = Res.string.settings_downloads_per_server,
    description = Res.string.settings_search_per_server.text(),
    keywords = Res.string.settings_search_per_server_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Downloads,
    title = Res.string.settings_downloads_retries,
    keywords = Res.string.settings_search_retries_keywords,
  ),
  // Speed
  SettingsIndexEntry(
    category = SettingsCategory.Speed,
    title = Res.string.settings_speed_mode,
    description = Res.string.settings_search_speed_mode.text(),
    keywords = Res.string.settings_search_speed_mode_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Speed,
    title = Res.string.settings_speed_full_cap,
    description = Res.string.settings_search_full_speed_cap.text(),
    keywords = Res.string.settings_search_full_speed_cap_keywords,
    anchors = listOf(Res.string.settings_speed_full_cap, Res.string.settings_speed_limit),
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Speed,
    title = Res.string.settings_speed_slow_lane,
    keywords = Res.string.settings_search_slow_lane_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Speed,
    title = Res.string.settings_speed_rules,
    description = Res.string.settings_search_rules.text(),
    keywords = Res.string.settings_search_rules_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Speed,
    title = Res.string.settings_speed_connections,
    keywords = Res.string.settings_search_connections_keywords,
  ),
  // Network
  SettingsIndexEntry(
    category = SettingsCategory.Network,
    title = Res.string.settings_network_spread,
    description = Res.string.settings_search_networks.text(),
    keywords = Res.string.settings_search_networks_keywords,
    anchors = listOf(Res.string.settings_network_networks, Res.string.settings_network_spread),
  ),
  // BitTorrent
  SettingsIndexEntry(
    category = SettingsCategory.BitTorrent,
    title = Res.string.settings_torrent_trackers,
    description = Res.string.settings_search_trackers.text(),
    keywords = Res.string.settings_search_trackers_keywords,
  ),
  // Sharing
  SettingsIndexEntry(
    category = SettingsCategory.Sharing,
    title = Res.string.settings_sharing_pair,
    description = Res.string.settings_search_pair.text(),
    keywords = Res.string.settings_search_pair_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Sharing,
    title = Res.string.settings_sharing_reachable,
    keywords = Res.string.settings_search_reachable_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Sharing,
    title = Res.string.settings_sharing_port,
    keywords = Res.string.settings_search_port_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Sharing,
    title = Res.string.settings_sharing_access_code,
    keywords = Res.string.settings_search_code_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Sharing,
    title = Res.string.settings_sharing_discoverable,
    keywords = Res.string.settings_search_discoverable_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Sharing,
    title = Res.string.settings_sharing_websites,
    keywords = Res.string.settings_search_websites_keywords,
  ),
  SettingsIndexEntry(
    category = SettingsCategory.Sharing,
    title = Res.string.settings_sharing_auto_start,
    keywords = Res.string.settings_search_auto_start_keywords,
  ),
)
