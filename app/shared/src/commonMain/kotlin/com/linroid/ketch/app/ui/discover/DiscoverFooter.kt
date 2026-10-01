package com.linroid.ketch.app.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.DeviceOption
import com.linroid.ketch.app.components.DeviceTargetChip
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.instance.DevicePresence
import com.linroid.ketch.app.state.AiDiscoverState
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.deviceId
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.util.formatBytes
import kotlinx.coroutines.Job

/**
 * The bar under the results: how much is selected, the device it goes to (with two devices or
 * more), Add now, and Review & add, which opens the add sheet with the selection.
 *
 * @param stacked puts the buttons on a row of their own, for narrow pages.
 * @param inset where the page's content starts.
 */
@Composable
internal fun DiscoverFooter(
  state: AppState,
  results: AiDiscoverState.Results,
  stacked: Boolean,
  inset: Dp,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val draft = state.aiDiscover.draft
  val selected = draft.selectedCandidates(results)
  var adding by remember { mutableStateOf<Job?>(null) }
  LaunchedEffect(adding) {
    adding?.join()
    adding = null
  }
  val busy = adding != null
  val summary = selectionSummary(selected.size, selected.map { it.fileSize })
  val addNow = {
    adding = state.addDiscovered(selected)
  }
  val review = { state.reviewDiscovered(selected) }
  Spacer(Modifier.fillMaxWidth().height(HairlineWidth).background(colors.hairline))
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = Modifier
      .fillMaxWidth()
      .background(colors.surface)
      .padding(horizontal = inset, vertical = spacing.s3),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier.fillMaxWidth().heightIn(min = KetchTheme.density.buttonMedium),
    ) {
      Text(
        text = summary,
        style = KetchTheme.typography.label,
        color = if (selected.isEmpty()) colors.textSecondary else colors.textPrimary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.weight(1f),
      )
      TargetChip(state)
      if (!stacked) {
        KetchButton(
          text = "Add now",
          onClick = addNow,
          variant = KetchButtonVariant.Secondary,
          enabled = selected.isNotEmpty(),
          loading = busy,
        )
        KetchButton(
          text = reviewLabel(selected.size),
          onClick = review,
          enabled = selected.isNotEmpty() && !busy,
        )
      }
    }
    if (stacked) {
      Row(horizontalArrangement = Arrangement.spacedBy(spacing.s2)) {
        KetchButton(
          text = "Add now",
          onClick = addNow,
          variant = KetchButtonVariant.Secondary,
          enabled = selected.isNotEmpty(),
          loading = busy,
          modifier = Modifier.weight(1f),
        )
        KetchButton(
          text = reviewLabel(selected.size),
          onClick = review,
          enabled = selected.isNotEmpty() && !busy,
          modifier = Modifier.weight(1f),
        )
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
  val draft = state.aiDiscover.draft
  val selectedId = draft.target?.takeIf { id -> devices.any { it.deviceId == id } }
    ?: active?.deviceId
    ?: return
  DeviceTargetChip(
    selectedId = selectedId,
    options = devices.map(::targetOption),
    onSelect = { draft.target = it.id },
  )
}

private fun targetOption(device: DevicePresence): DeviceOption = DeviceOption(
  id = device.deviceId,
  name = device.name,
  health = device.health,
  pennantName = device.entry.label,
  summary = device.disk?.usableBytes?.takeIf { it > 0 }?.let { "${formatBytes(it)} free" },
)

/** "Select downloads to add", or "2 selected · 1.2 GB" when every size is known. */
internal fun selectionSummary(count: Int, sizes: List<Long?>): String {
  if (count == 0) return "Select downloads to add"
  val known = sizes.filterNotNull().filter { it > 0 }
  return if (known.size == sizes.size) {
    "$count selected · ${formatBytes(known.sum())}"
  } else {
    "$count selected"
  }
}

private fun reviewLabel(count: Int): String =
  if (count > 0) "Review & add $count" else "Review & add"

private val HairlineWidth: Dp = 1.dp
