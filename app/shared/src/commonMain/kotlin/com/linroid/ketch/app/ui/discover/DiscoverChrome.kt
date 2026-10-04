package com.linroid.ketch.app.ui.discover

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands

/**
 * What the window's shell and the Discover page share: whether the history floats over the chat,
 * which the phone's top bar opens, and the requests to put the keyboard in the composer, such as
 * `⌘E` while Discover shows. It lives as long as the window; whether the docked history shows is
 * a saved preference instead.
 */
@Stable
internal class DiscoverChrome {
  /** Whether the history shows over the chat: as a card on medium pages, a sheet on phones. */
  var historyOpen: Boolean by mutableStateOf(false)

  /** Counts up with each request for the composer; see [focusComposer]. */
  var composerRequests: Int by mutableIntStateOf(0)
    private set

  /** Asks the Discover page to put the keyboard in the composer, once it shows one. */
  fun focusComposer() {
    composerRequests++
  }

  /**
   * Runs one of the Discover page's own keys, [KetchCommands.DiscoverNewSearch],
   * [KetchCommands.DiscoverHistory] or Esc ([KetchCommands.DiscoverStop]) closing the floating
   * history, for the window while the keyboard is not on the page; returns whether it did. Set
   * while the page shows.
   */
  var pageCommand: ((KetchCommand) -> Boolean)? = null
}

/** The window's [DiscoverChrome]; `null` outside the shell, where the page keeps its own. */
internal val LocalDiscoverChrome = staticCompositionLocalOf<DiscoverChrome?> { null }
