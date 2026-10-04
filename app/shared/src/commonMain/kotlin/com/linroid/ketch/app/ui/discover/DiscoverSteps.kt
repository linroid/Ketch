package com.linroid.ketch.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.app.components.KetchDot
import com.linroid.ketch.app.components.KetchSpinner
import com.linroid.ketch.app.components.OptionalTooltip
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.DiscoveryStep
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.inspector.Disclosure
import com.linroid.ketch.app.ui.inspector.TextLink
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.discover_show_less
import ketch.app.shared.generated.resources.discover_show_more
import ketch.app.shared.generated.resources.discover_step_starting
import ketch.app.shared.generated.resources.discover_steps_details
import ketch.app.shared.generated.resources.discover_steps_earlier
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The steps the agent reported, as a line of "✓ Plan  ✓ Searching  ◌ Filtering…". While
 * [running], the newest step spins and what the agent said about it shows below, folded to four
 * lines with Show more; hovering a step shows the start of what it said. Only the last few steps
 * show, after a count of the earlier ones; Details unfolds every finished step with all the agent
 * said, which can be selected and copied. While [running], screen readers hear each new step.
 *
 * @param turnId the turn the steps belong to, which keeps Details open or closed for it.
 * @param interrupted whether the search stopped or failed during its newest step, which then
 *   shows a quiet dot instead of a check.
 */
@Composable
internal fun DiscoverSteps(
  turnId: String,
  steps: List<DiscoveryStep>,
  running: Boolean,
  modifier: Modifier = Modifier,
  interrupted: Boolean = false,
) {
  if (steps.isEmpty() && !running) return
  val spacing = KetchTheme.spacing
  val colors = KetchTheme.colors
  val shown = steps.takeLast(MAX_SHOWN)
  val hidden = steps.size - shown.size
  var open by rememberSaveable(turnId) { mutableStateOf(false) }
  // Folded again for each new step.
  var expanded by rememberSaveable(steps.size) { mutableStateOf(false) }
  var overflows by remember(steps.size) { mutableStateOf(false) }
  val detail = if (running) shown.lastOrNull()?.detail.orEmpty().trim() else ""
  // The running step shows above with what it said, so opening Details moves nothing.
  val listed = if (running) steps.dropLast(1) else steps
  Column(modifier, verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
    Column(
      verticalArrangement = Arrangement.spacedBy(spacing.s1),
      // Only a search at work reports its steps as they come; finished ones stay quiet.
      modifier = Modifier.semantics { if (running) liveRegion = LiveRegionMode.Polite },
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
          val last = index == shown.lastIndex
          val mark = when {
            !last -> StepMark.Done
            running -> StepMark.Current
            interrupted -> StepMark.Unfinished
            else -> StepMark.Done
          }
          StepItem(step.title, step.detail, mark)
        }
        if (shown.isEmpty()) {
          StepItem(stringResource(Res.string.discover_step_starting), detail = "", StepMark.Current)
        }
      }
      if (detail.isNotEmpty()) {
        Text(
          text = detail,
          style = KetchTheme.typography.caption,
          color = colors.textTertiary,
          maxLines = if (expanded) Int.MAX_VALUE else DETAIL_LINES,
          overflow = TextOverflow.Ellipsis,
          onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
        )
      }
    }
    if (detail.isNotEmpty() && (overflows || expanded)) {
      TextLink(
        text = stringResource(
          if (expanded) Res.string.discover_show_less else Res.string.discover_show_more,
        ),
        onClick = { expanded = !expanded },
      )
    }
    if (hidden > 0 || listed.any { it.detail.isNotBlank() }) {
      Disclosure(stringResource(Res.string.discover_steps_details), open) { open = !open }
      AnimatedVisibility(open) { StepDetails(listed) }
    }
  }
}

/** What leads a step: a check once done, a spinner while it runs, a dot if it never finished. */
private enum class StepMark { Done, Current, Unfinished }

@Composable
private fun StepItem(title: String, detail: String, mark: StepMark) {
  val colors = KetchTheme.colors
  val current = mark == StepMark.Current
  val item = @Composable {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
    ) {
      Box(Modifier.size(MarkSize), contentAlignment = Alignment.Center) {
        when (mark) {
          StepMark.Current -> KetchSpinner(size = MarkSize, color = colors.accent)
          StepMark.Done -> KetchIconImage(
            icon = KetchIcon.Check,
            size = MarkSize,
            tint = colors.status.completed.color,
          )
          StepMark.Unfinished -> KetchDot(colors.textTertiary, size = MarkSize / 2)
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
  // The start of the detail on one line; Details has all of it.
  OptionalTooltip(if (detail.isBlank()) null else detail.oneLine().take(MAX_TOOLTIP)) { item() }
}

/**
 * [steps] in the order the agent reported them: each one's whole title, and under it all the
 * agent said about it, line breaks kept, all of which can be selected and copied.
 */
@Composable
private fun StepDetails(steps: List<DiscoveryStep>) {
  val spacing = KetchTheme.spacing
  val colors = KetchTheme.colors
  SelectionContainer {
    Column(
      verticalArrangement = Arrangement.spacedBy(spacing.s2),
      // Lined up with the disclosure's label, as the lines under the access summary are.
      modifier = Modifier.padding(start = spacing.s4, bottom = spacing.s1),
    ) {
      for (step in steps) {
        Column(verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
          Text(
            text = step.title.oneLine(),
            style = KetchTheme.typography.caption,
            color = colors.textSecondary,
          )
          if (step.detail.isNotBlank()) {
            Text(
              text = step.detail.trim(),
              style = KetchTheme.typography.caption,
              color = colors.textTertiary,
            )
          }
        }
      }
    }
  }
}

/** The step's title, cut to fit the line of steps. */
private fun String.shortTitle(): String =
  oneLine().let { if (it.length > MAX_TITLE) it.take(MAX_TITLE - 1).trimEnd() + "…" else it }

private fun String.oneLine(): String = trim().replace(Whitespace, " ")

private val Whitespace = Regex("\\s+")
private const val MAX_SHOWN = 6
private const val MAX_TITLE = 40
private const val MAX_TOOLTIP = 240

/** Lines of the running step's detail shown before Show more. */
private const val DETAIL_LINES = 4
