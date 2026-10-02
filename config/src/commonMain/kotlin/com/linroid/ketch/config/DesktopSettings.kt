package com.linroid.ketch.config

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** What closing the main desktop window does. */
@Serializable
enum class CloseAction {
  /** Ask the first time downloads are active or queued. */
  @SerialName("ask")
  Ask,

  /** Hide the window and keep downloading from the menu bar or notification area. */
  @SerialName("background")
  Background,

  /** Quit the app. */
  @SerialName("quit")
  Quit,
}

/** What the Dock or taskbar icon badge shows. */
@Serializable
enum class DockBadgeMode {
  /** Downloading plus waiting tasks, or "!" for failures not seen yet. */
  @SerialName("active-count")
  ActiveCount,

  /** Only "!" for failures not seen yet. */
  @SerialName("failures-only")
  FailuresOnly,

  /** No badge. */
  @SerialName("off")
  Off,
}

/**
 * Desktop app behavior, persisted under `[desktop]`.
 *
 * Only the desktop app reads this section.
 *
 * @property closeAction what closing the main window does.
 * @property openAtLogin whether the user asked for Ketch to open at login; the
 *   login item itself lives with the operating system.
 * @property startHidden whether a launch at login starts hidden in the menu bar
 *   or notification area.
 * @property dockBadge what the Dock or taskbar badge shows.
 */
@Serializable
data class DesktopSettings(
  val closeAction: CloseAction = CloseAction.Ask,
  val openAtLogin: Boolean = false,
  val startHidden: Boolean = true,
  val dockBadge: DockBadgeMode = DockBadgeMode.ActiveCount,
)
