package com.linroid.ketch.app.ui.shell

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.ui.downloads.KetchLayoutInfo
import com.linroid.ketch.app.ui.downloads.LayoutTier
import com.linroid.ketch.app.ui.pulse.PulseBarState

/**
 * What the shell shows around the screens of [app]: the destination, Settings, the shortcut
 * sheet, the command palette, the phone's search and Pulse sheet, and the Pulse bar's popovers.
 *
 * @param destination destination shown first.
 * @param settingsOpen whether Settings shows first.
 */
@Stable
internal class ShellState(
  val app: AppState,
  destination: AppDestination = AppDestination.Downloads,
  settingsOpen: Boolean = false,
) {
  /** The destination shown; see [show]. */
  var destination: AppDestination by mutableStateOf(destination)
    private set

  /** Whether Settings shows over the destination. */
  var settingsOpen: Boolean by mutableStateOf(settingsOpen)
    private set

  /** Whether the keyboard shortcut sheet is open. */
  var shortcutsOpen: Boolean by mutableStateOf(false)

  /** Whether the command palette is open. */
  var paletteOpen: Boolean by mutableStateOf(false)

  /** Whether the phone's top bar is a search field; see [closeSearch]. */
  var searchOpen: Boolean by mutableStateOf(false)

  /** Whether the phone's Pulse sheet is open. */
  var pulseSheetOpen: Boolean by mutableStateOf(false)

  /** Destinations the navigation offers; the shell keeps it current. */
  var destinations: List<AppDestination> by mutableStateOf(AppDestination.entries)

  /** Layout of the window; the shell keeps it current. */
  var layout: KetchLayout by mutableStateOf(KetchLayout.of(KetchLayoutInfo.ExpandedWidth))

  /** Which Pulse bar popovers are open, so `⌘J` reaches Activity. */
  val pulseBar: PulseBarState = PulseBarState()

  /** The phone's collapsing top bar. */
  val chrome: PhoneChromeState = PhoneChromeState()

  /** Id of the device that was active before the current one, which a long press goes back to. */
  var previousDeviceId: String? by mutableStateOf(null)

  /** Asks for the Downloads search field once Downloads shows; counts up with each request. */
  var searchFocusRequests: Int by mutableIntStateOf(0)
    private set

  /**
   * Shows [destination] and closes Settings, and the phone's search when it leaves Downloads;
   * returns whether the navigation offers it.
   */
  fun show(destination: AppDestination): Boolean {
    if (destination !in destinations) return false
    if (destination != this.destination) chrome.expand()
    this.destination = destination
    closeSettings()
    if (destination != AppDestination.Downloads) closeSearch()
    return true
  }

  /**
   * Turns the phone's search field back into the top bar and clears the search, so the list it
   * filtered never stays filtered out of sight.
   */
  fun closeSearch() {
    if (!searchOpen) return
    searchOpen = false
    app.searchQuery = ""
  }

  /** Shows Settings; the app's request names the page. */
  fun openSettings() {
    settingsOpen = true
  }

  /** Closes Settings and forgets the request that opened it. */
  fun closeSettings() {
    settingsOpen = false
    app.closeSettings()
  }

  /**
   * Shows Downloads and puts the keyboard in its search: the phone's search field, or the
   * Downloads page's own once it shows.
   */
  fun focusSearch() {
    val switched = destination != AppDestination.Downloads || settingsOpen
    show(AppDestination.Downloads)
    if (layout.navigation == ShellNavigation.Phone) {
      searchOpen = true
    } else if (switched) {
      // The page that owns the field was not showing, so it missed the request.
      searchFocusRequests++
    }
  }

  /**
   * Collapses the sidebar to the rail, or expands it, on windows wide enough for it; returns
   * whether it did.
   */
  fun toggleSidebar(): Boolean {
    if (layout.tier != LayoutTier.Expanded) return false
    app.appSettings.saveUi { it.copy(sidebarCollapsed = !it.sidebarCollapsed) }
    return true
  }
}
