package com.linroid.ketch.app.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchGlyphBadge
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchMenuScope
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.state.AiDiscoverController
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.discover_delete_search
import ketch.app.shared.generated.resources.discover_history_clear
import ketch.app.shared.generated.resources.discover_history_hide
import ketch.app.shared.generated.resources.discover_history_show
import ketch.app.shared.generated.resources.discover_history_waiting
import ketch.app.shared.generated.resources.discover_new_search
import ketch.app.shared.generated.resources.discover_settings
import ketch.app.shared.generated.resources.discover_title
import ketch.app.shared.generated.resources.discover_turn_off
import ketch.app.shared.generated.resources.shell_more
import org.jetbrains.compose.resources.stringResource

/**
 * How Discover's history shows and is toggled, for the header's and the phone top bar's
 * History button.
 *
 * @property offered whether there is a history to show: always once Discover is set up, and
 *   while it is not only when searches are saved.
 * @property shown whether it shows, docked or floating.
 * @property toggle shows or hides it.
 */
internal class HistoryToggle(
  val offered: Boolean,
  val shown: Boolean,
  val toggle: () -> Unit,
)

/**
 * The page header of wide windows: "Discover", the History toggle with how many other searches
 * wait for an OK, then the ⋯ menu ([discoverMenu]), New search and the model Discover searches
 * with, which opens its settings. Until Discover is set up only the title, the menu and, with
 * saved searches, History show.
 */
@Composable
internal fun DiscoverHeader(
  state: AppState,
  available: Boolean,
  history: HistoryToggle,
  onNewSearch: () -> Unit,
) {
  val spacing = KetchTheme.spacing
  val controller = state.aiDiscover
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s1),
    modifier = Modifier
      .fillMaxWidth()
      .height(spacing.pageHeaderHeight)
      .padding(horizontal = spacing.pageHeaderPadding),
  ) {
    Text(
      text = stringResource(Res.string.discover_title),
      style = KetchTheme.typography.pageTitle,
      color = KetchTheme.colors.textPrimary,
      modifier = Modifier.padding(end = spacing.s1),
    )
    if (history.offered) HistoryButton(controller, history)
    // At the end; on a narrow page the model's name gives way first.
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s1, Alignment.End),
      modifier = Modifier.weight(1f),
    ) {
      MoreMenu(state)
      if (available) {
        KetchButton(
          text = stringResource(Res.string.discover_new_search),
          onClick = onNewSearch,
          variant = KetchButtonVariant.Secondary,
          size = KetchButtonSize.Small,
          leadingIcon = KetchIcon.Compose,
          shortcut = KetchCommands.DiscoverNewSearch.shortcutLabel(),
          modifier = Modifier.padding(start = spacing.s1),
        )
        KetchButton(
          text = modelLabel(state.aiSettings).resolve(),
          onClick = { state.openSettings(SettingsTarget(SettingsTarget.Page.Discover)) },
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
          leadingIcon = KetchIcon.Ai,
          tooltip = stringResource(Res.string.discover_settings),
          modifier = Modifier.weight(1f, fill = false),
        )
      }
    }
  }
}

/**
 * The phone top bar's actions on Discover, in place of search: History with how many other
 * searches wait for an OK, and New search once Discover is set up. The page's menu
 * ([discoverMenu]) leads the bar's ⋮.
 */
@Composable
internal fun DiscoverTopBarActions(
  state: AppState,
  chrome: DiscoverChrome,
) {
  val controller = state.aiDiscover
  val available = state.aiSettings.available
  if (available || controller.sessions.isNotEmpty()) {
    HistoryButton(
      controller = controller,
      history = HistoryToggle(
        offered = true,
        shown = chrome.historyOpen,
        toggle = { chrome.historyOpen = !chrome.historyOpen },
      ),
    )
  }
  if (available) {
    KetchIconButton(
      icon = KetchIcon.Compose,
      onClick = {
        controller.newSession()
        chrome.historyOpen = false
        // With a pointer, as in a narrow desktop window, the keyboard goes to the composer.
        chrome.focusComposer()
      },
      contentDescription = stringResource(Res.string.discover_new_search),
    )
  }
}

/**
 * The History toggle, selected while the history shows, with a count of the other searches
 * that wait for the user to allow a website; the shown one asks in its own thread.
 */
@Composable
private fun HistoryButton(controller: AiDiscoverController, history: HistoryToggle) {
  val touch = KetchTheme.density == KetchDensity.Comfortable
  val waiting = controller.approvals.map { it.sessionId }.distinct()
    .count { it != controller.currentId }
  val label = if (history.shown) {
    Res.string.discover_history_hide.text()
  } else {
    Res.string.discover_history_show.text()
  }
  val description = listOfNotNull(
    label,
    waiting.takeIf { it > 0 }?.let { Res.plurals.discover_history_waiting.text(it) },
  ).joinText()
  Box {
    KetchIconButton(
      icon = KetchIcon.History,
      onClick = history.toggle,
      selected = history.shown,
      contentDescription = description.resolve(),
      shortcut = if (touch) null else KetchCommands.DiscoverHistory.shortcutLabel(),
    )
    if (waiting > 0) {
      // On the glyph's corner, as on the navigation's glyphs, wherever the touch target ends.
      val density = KetchTheme.density
      val inset = (maxOf(density.iconButton, density.iconButtonTarget) - density.controlGlyph) / 2
      KetchGlyphBadge(
        count = waiting,
        modifier = Modifier
          .align(Alignment.TopEnd)
          .offset(x = BadgeShift - inset, y = inset - BadgeRise)
          .clearAndSetSemantics {},
      )
    }
  }
}

/** The header's ⋯, with the page's menu ([discoverMenu]). */
@Composable
private fun MoreMenu(state: AppState) {
  var open by remember { mutableStateOf(false) }
  Box {
    KetchIconButton(
      icon = KetchIcon.More,
      onClick = { open = true },
      contentDescription = stringResource(Res.string.shell_more),
    )
    KetchMenu(expanded = open, onDismissRequest = { open = false }) { discoverMenu(state) }
  }
}

/**
 * The Discover page's menu: Delete this search, while one shows, Clear history, Discover's
 * settings and Turn off Discover, which hides the destination until it is switched back on in
 * Settings.
 */
internal fun KetchMenuScope.discoverMenu(state: AppState) {
  val controller = state.aiDiscover
  item(
    label = Res.string.discover_delete_search.text(),
    onClick = { controller.currentId?.let(state::deleteDiscoverSession) },
    icon = KetchIcon.Trash,
    enabled = controller.currentId != null,
    destructive = true,
  )
  item(
    label = Res.string.discover_history_clear.text(),
    onClick = { state.clearDiscoverHistory() },
    icon = KetchIcon.History,
    enabled = controller.sessions.any { !it.running },
  )
  divider()
  item(
    label = Res.string.discover_settings.text(),
    onClick = { state.openSettings(SettingsTarget(SettingsTarget.Page.Discover)) },
    icon = KetchIcon.Settings,
  )
  item(
    label = Res.string.discover_turn_off.text(),
    onClick = { state.turnOffDiscover() },
    icon = KetchIcon.Close,
  )
}

// How far the History count reaches past its glyph's top and end: it covers only the glyph's
// corner, even the small glyph of a pointer layout.
private val BadgeShift: Dp = 10.dp
private val BadgeRise: Dp = 6.dp
