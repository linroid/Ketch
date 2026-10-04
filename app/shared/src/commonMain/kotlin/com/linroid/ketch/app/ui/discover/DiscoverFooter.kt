package com.linroid.ketch.app.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.app.components.DeviceOption
import com.linroid.ketch.app.components.DeviceTargetChip
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.DiscoverSession
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.inspector.FirstThatFits
import com.linroid.ketch.config.SiteNames
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.device_free_space
import ketch.app.shared.generated.resources.discover_add_now
import ketch.app.shared.generated.resources.discover_add_options
import ketch.app.shared.generated.resources.discover_add_to
import ketch.app.shared.generated.resources.discover_clear_selection
import ketch.app.shared.generated.resources.discover_review_add
import ketch.app.shared.generated.resources.discover_review_add_count
import ketch.app.shared.generated.resources.discover_select_to_add
import ketch.app.shared.generated.resources.discover_selected
import ketch.app.shared.generated.resources.discover_selected_earlier
import kotlinx.coroutines.Job
import org.jetbrains.compose.resources.stringResource

/**
 * The add bar over the composer: a button that clears the selection, how much of [session] is
 * selected, saying how much of it comes from messages before the newest, the device it goes to
 * (with two devices or more), Add now, and Review & add, which opens the add sheet with the
 * selection.
 *
 * On a phone it is one row, "2 selected" with Add now and a button for a menu that holds Review
 * & add and the devices to add to.
 *
 * @param phone lays it out for a phone, as one row.
 * @param stacked puts the buttons on a row of their own, for narrow pages.
 */
@Composable
internal fun DiscoverAddBar(
  state: AppState,
  session: DiscoverSession,
  phone: Boolean,
  stacked: Boolean,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val selected = state.aiDiscover.selectedCandidates()
  var adding by remember { mutableStateOf<Job?>(null) }
  LaunchedEffect(adding) {
    adding?.join()
    adding = null
  }
  val busy = adding != null
  val earlier = earlierThan(session, selected)
  val addNow = @Composable { buttonModifier: Modifier ->
    KetchButton(
      text = stringResource(Res.string.discover_add_now),
      onClick = { adding = state.addDiscovered(selected) },
      variant = if (phone) KetchButtonVariant.Primary else KetchButtonVariant.Secondary,
      enabled = selected.isNotEmpty(),
      loading = busy,
      modifier = buttonModifier,
    )
  }
  val review = @Composable { buttonModifier: Modifier ->
    KetchButton(
      text = reviewLabel(selected.size),
      onClick = { state.reviewDiscovered(selected) },
      enabled = selected.isNotEmpty() && !busy,
      modifier = buttonModifier,
    )
  }
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = modifier.fillMaxWidth(),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier.fillMaxWidth().heightIn(min = KetchTheme.density.buttonMedium),
    ) {
      if (selected.isNotEmpty()) {
        // Earlier messages' results may be far up the chat; this lets go of them all at once.
        KetchIconButton(
          icon = KetchIcon.Close,
          onClick = state.aiDiscover::clearSelection,
          enabled = !busy,
          contentDescription = stringResource(Res.string.discover_clear_selection),
        )
      }
      if (phone) {
        // A phone's row leaves the summary little room beside the buttons, so the size and how
        // many come from earlier go under the count.
        SelectionLines(selected, earlier, Modifier.weight(1f))
      } else {
        // One line while it fits beside the buttons, as on a wide page in English; else two.
        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.weight(1f)) {
          FirstThatFits(count = 2) { variant ->
            if (variant == 0) {
              Text(
                text = selectionSummary(selected, earlier).resolve(),
                style = KetchTheme.typography.label,
                color = if (selected.isEmpty()) colors.textSecondary else colors.textPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            } else {
              SelectionLines(selected, earlier)
            }
          }
        }
      }
      if (phone) {
        addNow(Modifier)
        AddOptions(state, selected, busy)
      } else {
        TargetChip(state)
        if (!stacked) {
          addNow(Modifier)
          review(Modifier)
        }
      }
    }
    if (stacked && !phone) {
      Row(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
        addNow(Modifier.weight(1f))
        review(Modifier.weight(1f))
      }
    }
  }
}

/**
 * How much is selected on two lines: "2 selected" over "842.0 MB · 1 from earlier", or "Select
 * downloads to add" alone.
 */
@Composable
private fun SelectionLines(
  selected: List<AiCandidate>,
  earlier: Int,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  Column(modifier) {
    Text(
      // While a phone's bar leaves, the selection may be empty already.
      text = if (selected.isEmpty()) {
        Res.string.discover_select_to_add.text().resolve()
      } else {
        Res.plurals.discover_selected.text(selected.size).resolve()
      },
      style = KetchTheme.typography.label,
      color = if (selected.isEmpty()) colors.textSecondary else colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
    selectionDetails(selected, earlier)?.let { details ->
      Text(
        text = details.resolve(),
        style = KetchTheme.typography.caption,
        color = colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

/** A phone's ⋮ beside Add now: Review & add, and with two devices or more, the device to add to. */
@Composable
private fun AddOptions(state: AppState, selected: List<AiCandidate>, busy: Boolean) {
  var open by remember { mutableStateOf(false) }
  val presence = remember(state) { state.instanceManager.presence }
  val devices by presence.collectAsState()
  val active by state.activeInstance.collectAsState()
  val controller = state.aiDiscover
  val targetId = controller.target?.takeIf { id -> devices.any { it.deviceId == id } }
    ?: active?.deviceId
  val title = stringResource(Res.string.discover_add_options)
  Box {
    KetchIconButton(
      icon = KetchIcon.More,
      onClick = { open = true },
      enabled = selected.isNotEmpty(),
      contentDescription = title,
    )
    KetchMenu(expanded = open, onDismissRequest = { open = false }, title = title) {
      item(
        label = if (selected.isEmpty()) {
          Res.string.discover_review_add.text()
        } else {
          Res.string.discover_review_add_count.text(selected.size)
        },
        onClick = { state.reviewDiscovered(selected) },
        icon = KetchIcon.Open,
        enabled = selected.isNotEmpty() && !busy,
      )
      if (devices.size >= 2) {
        divider()
        header(Res.string.discover_add_to.text())
        for (device in devices) {
          item(
            label = device.name,
            onClick = { controller.target = device.deviceId },
            checked = device.deviceId == targetId,
          )
        }
      }
    }
  }
}

/** "On: (NB) NAS-Basement ▾", shown when there is more than one device to add to. */
@Composable
private fun TargetChip(state: AppState) {
  val presence = remember(state) { state.instanceManager.presence }
  val devices by presence.collectAsState()
  if (devices.size < 2) return
  val active by state.activeInstance.collectAsState()
  val controller = state.aiDiscover
  val selectedId = controller.target?.takeIf { id -> devices.any { it.deviceId == id } }
    ?: active?.deviceId
    ?: return
  DeviceTargetChip(
    selectedId = selectedId,
    options = devices.map(::targetOption),
    onSelect = { controller.target = it.id },
  )
}

private fun targetOption(device: DevicePresence): DeviceOption = DeviceOption(
  id = device.deviceId,
  name = device.name,
  health = device.health,
  pennantName = device.entry.label,
  summary = device.disk?.usableBytes?.takeIf { it > 0 }
    ?.let { Res.string.device_free_space.text(sizeText(it)) },
)

/** How many of [selected] are not among the results of [session]'s newest message. */
internal fun earlierThan(session: DiscoverSession, selected: List<AiCandidate>): Int {
  val newest = session.turns.lastOrNull() ?: return 0
  val latest = session.visible(newest).mapTo(mutableSetOf()) { SiteNames.canonicalUrl(it.url) }
  return selected.count { SiteNames.canonicalUrl(it.url) !in latest }
}

/**
 * "Select downloads to add", or "2 selected · 1.2 GB" when every size is known, followed by
 * "1 from earlier" when [earlier] of them come from messages before the newest.
 */
internal fun selectionSummary(selected: List<AiCandidate>, earlier: Int = 0): UiText {
  if (selected.isEmpty()) return Res.string.discover_select_to_add.text()
  return listOfNotNull(
    Res.plurals.discover_selected.text(selected.size),
    selectionDetails(selected, earlier),
  ).joinText()
}

/**
 * What follows the count of [selected]: "1.2 GB" when every size is known, and "1 from earlier"
 * when [earlier] of them come from messages before the newest; `null` when neither applies.
 */
internal fun selectionDetails(selected: List<AiCandidate>, earlier: Int = 0): UiText? {
  val sizes = selected.map { it.fileSize?.takeIf { size -> size > 0 } }
  val known = sizes.filterNotNull()
  return listOfNotNull(
    known.takeIf { selected.isNotEmpty() && it.size == sizes.size }?.let { sizeText(it.sum()) },
    earlier.takeIf { it > 0 }?.let { Res.plurals.discover_selected_earlier.text(it) },
  ).takeIf { it.isNotEmpty() }?.joinText()
}

@Composable
private fun reviewLabel(count: Int): String = if (count > 0) {
  stringResource(Res.string.discover_review_add_count, count)
} else {
  stringResource(Res.string.discover_review_add)
}
