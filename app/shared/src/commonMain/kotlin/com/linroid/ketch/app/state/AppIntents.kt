package com.linroid.ketch.app.state

/**
 * Asks the shell to open the add sheet.
 *
 * @property text text to put in the input, such as pasted links or a cURL command.
 * @property seeds links that arrive with metadata, such as a capture from the browser.
 * @property targetDeviceId device to add to; `null` uses the active device.
 * @property editTask task whose options the sheet edits instead of adding a new one.
 * @property retryOf failed task the sheet retries with new options.
 */
data class IntakeRequest(
  val text: String = "",
  val seeds: List<IntakeSeed> = emptyList(),
  val targetDeviceId: String? = null,
  val editTask: TaskKey? = null,
  val retryOf: TaskKey? = null,
)

/**
 * A link handed to the add sheet with what is already known about it.
 *
 * @property url link to download.
 * @property fileName name to save as; `null` lets the source decide.
 * @property headers request headers, such as the cookies and referrer of a browser capture.
 * @property properties bookkeeping for [com.linroid.ketch.api.DownloadRequest.properties],
 *   such as `ketch.origin`.
 */
data class IntakeSeed(
  val url: String,
  val fileName: String? = null,
  val headers: Map<String, String> = emptyMap(),
  val properties: Map<String, String> = emptyMap(),
)

/**
 * Asks the shell to open Settings at [page].
 *
 * @property page page to show.
 * @property deviceId device whose page to show; `null` uses the active device. App pages
 *   ignore it.
 */
data class SettingsTarget(
  val page: Page,
  val deviceId: String? = null,
) {
  /**
   * Settings pages, app pages first.
   *
   * @property isDevicePage whether the page edits one device's settings rather than the app's.
   */
  enum class Page(val isDevicePage: Boolean) {
    General(isDevicePage = false),
    Notifications(isDevicePage = false),
    Integration(isDevicePage = false),
    Discover(isDevicePage = false),
    About(isDevicePage = false),
    Downloads(isDevicePage = true),
    Speed(isDevicePage = true),
    Network(isDevicePage = true),
    BitTorrent(isDevicePage = true),
    Sharing(isDevicePage = true),
  }
}

/**
 * Asks the shell to open Discover and search.
 *
 * @property query what to look for.
 * @property sites websites to limit the search to; empty searches the whole web.
 */
data class DiscoverRequest(
  val query: String,
  val sites: List<String> = emptyList(),
)
