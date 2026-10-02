package com.linroid.ketch.config

import com.linroid.ketch.api.DownloadPriority
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** How the Downloads page lays out its tasks. */
@Serializable
enum class DownloadsLayout {
  /** A table with pointer input and room for it, two-line rows otherwise. */
  @SerialName("auto")
  Auto,

  /** Two-line rows. */
  @SerialName("list")
  List,

  /** A table with sortable columns. */
  @SerialName("table")
  Table,
}

/** What the apps do with a link found on the clipboard. */
@Serializable
enum class ClipboardMode {
  /** Prefill the add sheet with it. */
  @SerialName("fill")
  Fill,

  /** Offer it in a chip or tile, read only when used. */
  @SerialName("suggest")
  Suggest,

  /** Ignore the clipboard. */
  @SerialName("off")
  Off,
}

/** How dense the UI is. */
@Serializable
enum class DensityMode {
  /** Compact with a pointer, comfortable with touch. */
  @SerialName("auto")
  Auto,

  @SerialName("compact")
  Compact,

  @SerialName("comfortable")
  Comfortable,
}

/**
 * What the add sheet remembers for one device.
 *
 * @property folder folder the last downloads were saved to; `null` uses the
 *   device's default directory.
 * @property priority priority of the last downloads.
 * @property connections connections per download of the last downloads; `0`
 *   uses the device default.
 * @property favoriteFolders folders pinned in the add sheet's folder menu.
 */
@Serializable
data class IntakePreferences(
  val folder: String? = null,
  val priority: DownloadPriority = DownloadPriority.NORMAL,
  val connections: Int = 0,
  val favoriteFolders: List<String> = emptyList(),
)

/**
 * App UI state remembered between launches, persisted under `[ui]`.
 *
 * Only the apps read this section. Maps keyed by tab use the status filter's
 * name; maps keyed by device use the device id.
 *
 * @property layout how the Downloads page lays out its tasks.
 * @property table table columns per tab: widths, order and visibility, encoded
 *   by the table.
 * @property sort sort order per tab, encoded by the list.
 * @property sidebarCollapsed whether the sidebar is collapsed to the rail.
 * @property lastDeviceId device that was active when the app last ran; `null`
 *   until the user switches devices.
 * @property inspectorWidth width of the docked inspector, in dp.
 * @property intake what the add sheet remembers per device: the options of the
 *   last add (folder, priority and connections) and the pinned folders.
 * @property intakeAdvancedOpen whether the add sheet's Advanced section is open.
 * @property clipboardMode what to do with a link on the clipboard; `null` uses
 *   the platform default (fill on desktop, suggest on phones and the web).
 * @property quickAdd whether pasting one link adds it at once; `null` uses the
 *   platform default (on for desktop and the web).
 * @property lastClipHash hash of the clipboard text last offered, so the same
 *   link is not offered twice.
 * @property observedPeak highest total download speed seen in the last 7 days,
 *   in bytes per second; `0` until there is history.
 * @property observedPeakAt when [observedPeak] was seen, in epoch milliseconds.
 * @property onboardingVersion version of the welcome flow the user finished;
 *   `0` when it has not been shown.
 * @property setupChecklistShownAt when the launchpad's setup checklist was first
 *   shown, in epoch milliseconds; `0` until then. It hides itself 7 days later.
 * @property setupChecklistDismissed whether the user closed the setup checklist.
 * @property density how dense the UI is.
 * @property reduceMotion whether to reduce motion; `false` follows the system.
 * @property settingsPage name of the Settings page shown last, which Settings reopens on;
 *   `null` until Settings has been opened.
 */
@Serializable
data class UiPreferences(
  val layout: DownloadsLayout = DownloadsLayout.Auto,
  // ktoml keeps the quotes of keys such as "nas.local" in maps of plain values, so only maps of
  // tables, like intake, can be keyed by device id.
  val table: Map<String, String> = emptyMap(),
  val sort: Map<String, String> = emptyMap(),
  val sidebarCollapsed: Boolean = false,
  val lastDeviceId: String? = null,
  val inspectorWidth: Int = DEFAULT_INSPECTOR_WIDTH,
  val intake: Map<String, IntakePreferences> = emptyMap(),
  val intakeAdvancedOpen: Boolean = false,
  val clipboardMode: ClipboardMode? = null,
  val quickAdd: Boolean? = null,
  val lastClipHash: String? = null,
  val observedPeak: Long = 0,
  val observedPeakAt: Long = 0,
  val onboardingVersion: Int = 0,
  val setupChecklistShownAt: Long = 0,
  val setupChecklistDismissed: Boolean = false,
  val density: DensityMode = DensityMode.Auto,
  val reduceMotion: Boolean = false,
  val settingsPage: String? = null,
) {
  companion object {
    /** Docked inspector width until the user resizes it, in dp. */
    const val DEFAULT_INSPECTOR_WIDTH: Int = 320
  }
}
