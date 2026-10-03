package com.linroid.ketch.app.ui.shell

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusTarget
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.LocalWindowInfo
import com.linroid.ketch.app.input.CommandScope
import com.linroid.ketch.app.input.KetchCommand
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.input.ShortcutContext
import com.linroid.ketch.app.input.ShortcutMatcher

/**
 * Commands whose chords the host runs before the window's content sees the key, such as those
 * the macOS menu bar owns; [ShortcutHost] leaves their keys alone, so each runs once. Empty
 * unless the host provides it.
 */
val LocalHostShortcuts: ProvidableCompositionLocal<Set<KetchCommand>> =
  staticCompositionLocalOf { emptySet() }

/**
 * Listens for the global chords of [KetchCommands] anywhere in [content] and runs them through
 * [onCommand], which returns whether it used the key.
 *
 * Chords with ⌘ or Ctrl run before the focused control sees them. Chords a focused text field
 * keeps, `⌘V` and `⌘Z`, and keys that type, such as the web's `n` and `/`, run only after the
 * focused control left them unused. While nothing else has the focus, this host takes it, so
 * the keys still arrive.
 *
 * @param overlay the open add sheet or palette, whose own chords come first.
 * @param page the scope of the page shown, such as [CommandScope.Discover], whose chords run
 *   after the focused control left them unused, while no overlay is open: the page handles them
 *   itself while the keyboard is in it, and this runs them while it is not.
 */
@Composable
internal fun ShortcutHost(
  onCommand: (KetchCommand) -> Boolean,
  modifier: Modifier = Modifier,
  overlay: CommandScope? = null,
  page: CommandScope? = null,
  content: @Composable () -> Unit,
) {
  val hostShortcuts = LocalHostShortcuts.current
  val matcher = remember(hostShortcuts) { shellShortcuts(hostShortcuts) }
  val run by rememberUpdatedState(onCommand)
  val currentOverlay by rememberUpdatedState(overlay)
  val currentPage by rememberUpdatedState(page)
  val focus = remember { FocusRequester() }
  var hasFocus by remember { mutableStateOf(true) }
  val windowInfo = LocalWindowInfo.current
  fun handle(event: KeyEvent, beforeFocused: Boolean): Boolean {
    // Before the focused control, act as if it were a text field, so chords it may keep wait.
    val context = ShortcutContext(overlay = currentOverlay, textFieldFocused = beforeFocused)
    val command = matcher.match(event, context)
    if (command?.scope == CommandScope.Global && run(command)) return true
    val shown = currentPage
    if (beforeFocused || shown == null || currentOverlay != null) return false
    val pageCommand = matcher.match(event, ShortcutContext(overlay = shown)) ?: return false
    return pageCommand.scope == shown && run(pageCommand)
  }
  Box(
    propagateMinConstraints = true,
    modifier = modifier
      .onPreviewKeyEvent { handle(it, beforeFocused = true) }
      .onKeyEvent { handle(it, beforeFocused = false) }
      .onFocusChanged { hasFocus = it.hasFocus }
      .focusRequester(focus)
      .focusTarget(),
  ) {
    content()
  }
  // Takes the focus at start, and again whenever a focused control goes away, since keys only
  // reach a window whose content has the focus somewhere; a control that took it meanwhile, as
  // the next of a list does, keeps it.
  LaunchedEffect(focus, hasFocus, windowInfo.isWindowFocused) {
    if (hasFocus || !windowInfo.isWindowFocused) return@LaunchedEffect
    withFrameNanos {}
    if (!hasFocus) runCatching { focus.requestFocus() }
  }
}

/**
 * Matches the chords of [KetchCommands] except those of [hostShortcuts], which the host runs
 * itself.
 */
internal fun shellShortcuts(
  hostShortcuts: Set<KetchCommand>,
  platform: KeyboardPlatform = KeyboardPlatform.current,
): ShortcutMatcher =
  ShortcutMatcher(
    platform = platform,
    commands = KetchCommands.all.filterNot { it in hostShortcuts },
  )
