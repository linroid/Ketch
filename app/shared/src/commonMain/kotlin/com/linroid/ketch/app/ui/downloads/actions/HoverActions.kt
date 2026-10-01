package com.linroid.ketch.app.ui.downloads.actions

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.input.KeyboardPlatform
import com.linroid.ketch.app.state.RowAction
import com.linroid.ketch.app.state.TaskRow
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.list.RowCommands

/**
 * The buttons that fade in at the end of a row while the pointer is over it: Open and Show for a
 * finished file, otherwise the row's primary action, such as Pause, Retry or Start now, and "⋯",
 * which opens the row's menu. They act on this row alone and never change the selection. While a
 * command runs on the row, its button shows a spinner.
 *
 * Pointer only; touch rows show their primary action and open the menu with a long-press or
 * their own "⋯".
 *
 * @param visible whether the pointer is over the row, or the row holds the keyboard focus.
 */
@Composable
internal fun HoverActions(
  row: TaskRow,
  visible: Boolean,
  runner: RowActionRunner,
  modifier: Modifier = Modifier,
) {
  val motion = KetchTheme.motion
  LaunchedEffect(visible, row.key, row.state) { if (visible) runner.checkFile(row) }
  AnimatedVisibility(
    visible = visible,
    enter = fadeIn(tween(motion.micro, easing = motion.easeStandard)),
    exit = fadeOut(tween(motion.micro, easing = motion.easeStandard)),
    modifier = modifier,
  ) {
    HoverButtons(row, runner)
  }
}

@Composable
private fun HoverButtons(row: TaskRow, runner: RowActionRunner) {
  val pending by runner.state.pending.collectAsState()
  val busy = RowCommands.isBusy(row, pending)
  val buttons = hoverButtons(runner.hover(row), runner.menu(row))
  val actions = buttons.actions
  var menuOpen by remember { mutableStateOf(false) }
  val platform = KeyboardPlatform.current
  val revealLabel = runner.files?.revealLabel
  Row(
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    for (action in actions) {
      if (busy && action == actions.first() && action != RowAction.Open) {
        Box(Modifier.size(KetchTheme.density.iconButtonTarget), Alignment.Center) {
          KetchSpinner()
        }
        continue
      }
      KetchIconButton(
        icon = action.icon,
        contentDescription = rowActionLabel(action, revealLabel),
        shortcut = action.command?.shortcutLabel(platform),
        onClick = { runner.run(action, listOf(row)) },
      )
    }
    if (buttons.more) {
      Box {
        KetchIconButton(
          icon = KetchIcon.More,
          contentDescription = "More actions",
          onClick = { menuOpen = true },
          selected = menuOpen,
        )
        RowMenu(
          expanded = menuOpen,
          onDismissRequest = { menuOpen = false },
          rows = listOf(row),
          runner = runner,
        )
      }
    }
  }
}

/**
 * The hover buttons of a row.
 *
 * @property actions the action buttons, in order.
 * @property more whether "⋯" follows them.
 */
internal data class HoverButtons(val actions: List<RowAction>, val more: Boolean)

/**
 * The hover buttons of a row whose [hover] actions and [menu] are given: two buttons at most,
 * the last one "⋯" whenever the menu offers more than the actions shown.
 */
internal fun hoverButtons(hover: List<RowAction>, menu: List<RowAction>): HoverButtons {
  val all = hover.take(MAX_BUTTONS)
  if (all.size == MAX_BUTTONS || menu.size <= all.size) return HoverButtons(all, more = false)
  return HoverButtons(all.take(MAX_BUTTONS - 1), more = true)
}

/** At most two hover buttons, "⋯" included. */
private const val MAX_BUTTONS = 2
