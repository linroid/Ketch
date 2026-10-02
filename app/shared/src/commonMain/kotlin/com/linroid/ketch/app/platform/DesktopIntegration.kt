package com.linroid.ketch.app.platform

import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.staticCompositionLocalOf
import com.linroid.ketch.config.CloseAction
import com.linroid.ketch.config.DockBadgeMode

/**
 * A browser the desktop app found on this computer.
 *
 * @property name name to show, such as "Chrome".
 * @property extensionConnected whether the Ketch extension in this browser has connected.
 */
data class DetectedBrowser(
  val name: String,
  val extensionConnected: Boolean = false,
)

/**
 * How Ketch is wired into the browsers and the operating system. Only the desktop app reports
 * anything; elsewhere it stays empty.
 *
 * @property browsers browsers found on this computer.
 * @property extensionConnected whether the browser extension has connected from any browser.
 * @property magnetHandler whether Ketch opens `magnet:` links.
 * @property torrentFileHandler whether Ketch opens `.torrent` files.
 */
data class IntegrationStatus(
  val browsers: List<DetectedBrowser> = emptyList(),
  val extensionConnected: Boolean = false,
  val magnetHandler: Boolean = false,
  val torrentFileHandler: Boolean = false,
)

/** Live [IntegrationStatus], provided by the desktop app. */
val LocalIntegrationStatus: ProvidableCompositionLocal<IntegrationStatus> =
  compositionLocalOf { IntegrationStatus() }

/**
 * Desktop behaviors that shared settings, menus and pages can trigger. The desktop app provides
 * an implementation through [LocalDesktopHooks]; every hook does nothing by default.
 *
 * Callers save the matching `[desktop]` or `[integration]` setting themselves; a hook only makes
 * the operating system follow it. The suspending hooks touch files, the registry or other
 * processes, and throw when the operating system refuses.
 */
interface DesktopHooks {
  /** Whether these hooks do anything here, so settings can hide desktop-only rows. */
  val isSupported: Boolean get() = false

  /** Makes closing the main window follow [action] from now on. */
  fun setCloseAction(action: CloseAction) {}

  /** Adds or removes the login item that opens Ketch at login. */
  suspend fun setOpenAtLogin(enabled: Boolean) {}

  /** Makes a launch at login start hidden in the menu bar or notification area. */
  suspend fun setStartHidden(enabled: Boolean) {}

  /** Makes the Dock or taskbar badge follow [mode]. */
  fun setDockBadgeMode(mode: DockBadgeMode) {}

  /** Registers Ketch to open `magnet:` links; returns whether it now does. */
  suspend fun registerMagnetHandler(): Boolean = false

  /** Registers Ketch to open `.torrent` files; returns whether it now does. */
  suspend fun registerTorrentFileHandler(): Boolean = false

  /** Opens the folder that holds the log files. */
  suspend fun openLogsFolder() {}

  companion object {
    /** Hooks that do nothing, for platforms other than desktop. */
    val None: DesktopHooks = object : DesktopHooks {}
  }
}

/** [DesktopHooks] of the running app, [DesktopHooks.None] unless the desktop app provides them. */
val LocalDesktopHooks: ProvidableCompositionLocal<DesktopHooks> =
  staticCompositionLocalOf { DesktopHooks.None }
