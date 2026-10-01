package com.linroid.ketch.app.ui.feedback

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.EnterTransition
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier

/**
 * [items] in a column, each entering with [enter] and leaving with [exit]. An item that leaves
 * stays composed, showing its last value, until it has animated away.
 *
 * @param id identifies an item while its value changes.
 */
@Composable
internal fun <T : Any> AnimatedStack(
  items: List<T>,
  id: (T) -> Any,
  enter: EnterTransition,
  exit: ExitTransition,
  modifier: Modifier = Modifier,
  verticalArrangement: Arrangement.Vertical = Arrangement.Top,
  horizontalAlignment: Alignment.Horizontal = Alignment.Start,
  content: @Composable (T) -> Unit,
) {
  val shown = remember { LinkedHashMap<Any, Shown<T>>() }
  val ids = items.mapTo(HashSet(), id)
  shown.entries.removeAll { (key, entry) ->
    key !in ids && entry.visibility.isIdle && !entry.visibility.currentState
  }
  for (item in items) {
    shown.getOrPut(id(item)) { Shown(item, MutableTransitionState(false)) }.item = item
  }
  Column(
    modifier = modifier,
    verticalArrangement = verticalArrangement,
    horizontalAlignment = horizontalAlignment,
  ) {
    for ((key, entry) in shown) {
      key(key) {
        val item = entry.item
        entry.visibility.targetState = key in ids
        AnimatedVisibility(visibleState = entry.visibility, enter = enter, exit = exit) {
          content(item)
        }
      }
    }
  }
}

private class Shown<T>(var item: T, val visibility: MutableTransitionState<Boolean>)
