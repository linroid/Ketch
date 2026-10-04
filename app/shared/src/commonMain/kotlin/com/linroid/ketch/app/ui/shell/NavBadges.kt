package com.linroid.ketch.app.ui.shell

import androidx.compose.runtime.Composable
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.state.AppDestination
import com.linroid.ketch.app.state.AppState
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.discover_nav_waiting
import ketch.app.shared.generated.resources.discover_waiting
import org.jetbrains.compose.resources.pluralStringResource

/**
 * A count over a destination in the navigation.
 *
 * @property count how many, such as requests that wait for an OK.
 * @property description the destination with its count, as screen readers say it.
 * @property countDescription the count alone, for a badge read with the destination's label.
 */
internal class NavBadge(
  val count: Int,
  val description: String,
  val countDescription: String = description,
)

/**
 * The counts the navigation shows over destinations other than Downloads, whose marks are its
 * own: Discover's requests to open a website that wait for the user's OK.
 */
@Composable
internal fun navBadges(state: AppState): Map<AppDestination, NavBadge> {
  val waiting = state.discoverWaitingCount
  if (waiting == 0) return emptyMap()
  val label = AppDestination.Discover.label.resolve()
  val badge = NavBadge(
    count = waiting,
    description = pluralStringResource(Res.plurals.discover_nav_waiting, waiting, label, waiting),
    countDescription = pluralStringResource(Res.plurals.discover_waiting, waiting, waiting),
  )
  return mapOf(AppDestination.Discover to badge)
}
