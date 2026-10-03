package com.linroid.ketch.app.ui.discover

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.DiscoveryStep
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.discover_step_starting
import ketch.app.shared.generated.resources.discover_steps_earlier
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The steps the agent reported, as a line of "✓ Plan  ✓ Searching  ◌ Filtering…". While
 * [running], the newest step spins and what the agent said about it shows below; hovering a
 * step shows what it said too. Only the last few steps show, after a count of the earlier ones.
 */
@Composable
internal fun DiscoverSteps(
  steps: List<DiscoveryStep>,
  running: Boolean,
  modifier: Modifier = Modifier,
) {
  if (steps.isEmpty() && !running) return
  val spacing = KetchTheme.spacing
  val colors = KetchTheme.colors
  val shown = steps.takeLast(MAX_SHOWN)
  val hidden = steps.size - shown.size
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s1),
    modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
  ) {
    FlowRow(
      horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      verticalArrangement = Arrangement.spacedBy(spacing.s1),
    ) {
      if (hidden > 0) {
        Text(
          text = pluralStringResource(Res.plurals.discover_steps_earlier, hidden, hidden),
          style = KetchTheme.typography.caption,
          color = colors.textTertiary,
          modifier = Modifier.align(Alignment.CenterVertically),
        )
      }
      shown.forEachIndexed { index, step ->
        val current = running && index == shown.lastIndex
        StepItem(step.title, step.detail, current)
      }
      if (shown.isEmpty()) {
        StepItem(stringResource(Res.string.discover_step_starting), detail = "", current = true)
      }
    }
    val detail = shown.lastOrNull()?.detail.orEmpty()
    if (running && detail.isNotBlank()) {
      Text(
        text = detail.oneLine(),
        style = KetchTheme.typography.caption,
        color = colors.textTertiary,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
      )
    }
  }
}

@Composable
private fun StepItem(title: String, detail: String, current: Boolean) {
  val colors = KetchTheme.colors
  val item = @Composable {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
    ) {
      Box(Modifier.size(MarkSize), contentAlignment = Alignment.Center) {
        if (current) {
          KetchSpinner(size = MarkSize, color = colors.accent)
        } else {
          KetchIconImage(KetchIcon.Check, size = MarkSize, tint = colors.status.completed.color)
        }
      }
      Text(
        text = if (current) "${title.shortTitle()}…" else title.shortTitle(),
        style = KetchTheme.typography.caption,
        color = if (current) colors.textPrimary else colors.textSecondary,
        maxLines = 1,
      )
    }
  }
  if (detail.isBlank()) {
    item()
  } else {
    KetchTooltip(text = detail.oneLine().take(MAX_TOOLTIP)) { item() }
  }
}

/** The step's title, cut to fit the line of steps. */
private fun String.shortTitle(): String =
  oneLine().let { if (it.length > MAX_TITLE) it.take(MAX_TITLE - 1).trimEnd() + "…" else it }

private fun String.oneLine(): String = trim().replace(Whitespace, " ")

private val Whitespace = Regex("\\s+")
private val MarkSize: Dp = 12.dp
private const val MAX_SHOWN = 6
private const val MAX_TITLE = 40
private const val MAX_TOOLTIP = 240

