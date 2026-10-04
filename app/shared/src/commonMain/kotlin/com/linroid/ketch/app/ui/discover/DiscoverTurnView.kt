package com.linroid.ketch.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchTriStateCheckbox
import com.linroid.ketch.app.i18n.SEPARATOR
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.AccessNote
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.DiscoverTurn
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.inspector.Disclosure
import com.linroid.ketch.app.ui.inspector.TextLink
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_try_again
import ketch.app.shared.generated.resources.discover_access_allowed
import ketch.app.shared.generated.resources.discover_access_allowed_host
import ketch.app.shared.generated.resources.discover_access_denied
import ketch.app.shared.generated.resources.discover_access_denied_host
import ketch.app.shared.generated.resources.discover_clear_selection
import ketch.app.shared.generated.resources.discover_discard_all
import ketch.app.shared.generated.resources.discover_discarded_count
import ketch.app.shared.generated.resources.discover_error_title
import ketch.app.shared.generated.resources.discover_filtered_count
import ketch.app.shared.generated.resources.discover_limited_to
import ketch.app.shared.generated.resources.discover_none_body
import ketch.app.shared.generated.resources.discover_none_filtered
import ketch.app.shared.generated.resources.discover_none_title
import ketch.app.shared.generated.resources.discover_queued
import ketch.app.shared.generated.resources.discover_restore
import ketch.app.shared.generated.resources.discover_results
import ketch.app.shared.generated.resources.discover_search_everywhere
import ketch.app.shared.generated.resources.discover_select_all
import ketch.app.shared.generated.resources.discover_settings
import ketch.app.shared.generated.resources.discover_show_less
import ketch.app.shared.generated.resources.discover_show_more
import ketch.app.shared.generated.resources.discover_stopped
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What the user asked, in a bubble at the end of the thread's column at most 80% of its width,
 * with the websites the message was limited to under it.
 */
@Composable
internal fun UserMessage(turn: DiscoverTurn, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Column(horizontalAlignment = Alignment.End, modifier = modifier.fillMaxWidth()) {
    Box(contentAlignment = Alignment.CenterEnd, modifier = Modifier.fillMaxWidth(BUBBLE_SHARE)) {
      Text(
        text = turn.message,
        style = KetchTheme.typography.body,
        color = colors.textPrimary,
        modifier = Modifier
          .ketchSurface(
            level = KetchElevationLevel.E0,
            shape = KetchTheme.shapes.lg,
            fill = colors.surfaceSunken,
            border = colors.hairline,
          )
          .padding(horizontal = spacing.s4, vertical = spacing.s2 + spacing.s0_5),
      )
    }
    if (turn.sites.isNotEmpty()) {
      Text(
        text = stringResource(Res.string.discover_limited_to, turn.sites.joinToString(", ")),
        style = KetchTheme.typography.caption,
        color = colors.textTertiary,
        textAlign = TextAlign.End,
        modifier = Modifier.fillMaxWidth(BUBBLE_SHARE).padding(top = spacing.s1, end = spacing.s1),
      )
    }
  }
}

/** "Waiting for another search to finish", for a message that has not started. */
@Composable
internal fun QueuedNote(modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
    modifier = modifier,
  ) {
    KetchIconImage(KetchIcon.Queued, size = MarkSize, tint = colors.textTertiary)
    Text(
      text = stringResource(Res.string.discover_queued),
      style = KetchTheme.typography.caption,
      color = colors.textSecondary,
    )
  }
}

/**
 * "Allowed 2 sites · Denied 1 site", which unfolds into a line per website: how the user
 * answered the turn's requests to open them.
 */
@Composable
internal fun AccessLine(notes: List<AccessNote>, modifier: Modifier = Modifier) {
  var open by rememberSaveable { mutableStateOf(false) }
  val allowed = notes.filter { it.allowed }
  val denied = notes.filterNot { it.allowed }
  val summary = listOfNotNull(
    allowed.size.takeIf { it > 0 }?.let { Res.plurals.discover_access_allowed.text(it) },
    denied.size.takeIf { it > 0 }?.let { Res.plurals.discover_access_denied.text(it) },
  ).joinText()
  Column(modifier) {
    Disclosure(text = summary.resolve(), open = open, onToggle = { open = !open })
    AnimatedVisibility(open) {
      Column(
        verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s0_5),
        modifier = Modifier.padding(start = KetchTheme.spacing.s4, bottom = KetchTheme.spacing.s1),
      ) {
        for (note in allowed + denied) {
          val line = if (note.allowed) {
            Res.string.discover_access_allowed_host
          } else {
            Res.string.discover_access_denied_host
          }
          Text(
            text = stringResource(line, note.host),
            style = KetchTheme.typography.caption,
            color = KetchTheme.colors.textSecondary,
          )
        }
      }
    }
  }
}

/** The agent's short reply in plain text, folded to six lines with Show more when longer. */
@Composable
internal fun AgentSummary(text: String, modifier: Modifier = Modifier) {
  var expanded by rememberSaveable { mutableStateOf(false) }
  var overflows by remember { mutableStateOf(false) }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1)) {
    Text(
      text = text,
      style = KetchTheme.typography.body,
      color = KetchTheme.colors.textPrimary,
      maxLines = if (expanded) Int.MAX_VALUE else SUMMARY_LINES,
      overflow = TextOverflow.Ellipsis,
      onTextLayout = { if (!expanded) overflows = it.hasVisualOverflow },
    )
    if (overflows || expanded) {
      TextLink(
        text = stringResource(
          if (expanded) Res.string.discover_show_less else Res.string.discover_show_more,
        ),
        onClick = { expanded = !expanded },
      )
    }
  }
}

/**
 * "4 downloads", with the box that selects or clears every result of the turn, and Discard all.
 *
 * @param selected how many of [candidates] are selected.
 */
@Composable
internal fun ResultsHeader(
  candidates: List<AiCandidate>,
  selected: Int,
  onSelectAll: () -> Unit,
  onClearSelection: () -> Unit,
  onDiscardAll: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val spacing = KetchTheme.spacing
  val everything = selected == candidates.size
  val toggleAll = stringResource(
    if (everything) Res.string.discover_clear_selection else Res.string.discover_select_all,
  )
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = modifier.fillMaxWidth().heightIn(min = KetchTheme.density.tableRow),
  ) {
    KetchTriStateCheckbox(
      modifier = Modifier.checkboxSlot().semantics { contentDescription = toggleAll },
      state = when (selected) {
        0 -> ToggleableState.Off
        candidates.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
      },
      onClick = { if (everything) onClearSelection() else onSelectAll() },
    )
    Text(
      text = pluralStringResource(Res.plurals.discover_results, candidates.size, candidates.size),
      style = KetchTheme.typography.bodyStrong,
      color = KetchTheme.colors.textPrimary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
    KetchButton(
      text = stringResource(Res.string.discover_discard_all),
      onClick = onDiscardAll,
      variant = KetchButtonVariant.Ghost,
      size = KetchButtonSize.Small,
    )
  }
}

/** "2 discarded · Restore", which shows the turn's discarded results again. */
@Composable
internal fun DiscardedLine(count: Int, onRestore: () -> Unit, modifier: Modifier = Modifier) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
    Text(
      text = pluralStringResource(Res.plurals.discover_discarded_count, count, count) + SEPARATOR,
      style = KetchTheme.typography.caption,
      color = KetchTheme.colors.textTertiary,
    )
    TextLink(text = stringResource(Res.string.discover_restore), onClick = onRestore)
  }
}

/**
 * "2 hidden by the content filter · Settings": results of the turn that the content filter hid,
 * with Discover's settings, where it can be turned off.
 */
@Composable
internal fun FilteredLine(count: Int, onSettings: () -> Unit, modifier: Modifier = Modifier) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
    Text(
      text = pluralStringResource(Res.plurals.discover_filtered_count, count, count) + SEPARATOR,
      style = KetchTheme.typography.caption,
      color = KetchTheme.colors.textTertiary,
    )
    TextLink(text = stringResource(Res.string.discover_settings), onClick = onSettings)
  }
}

/**
 * What went wrong, with Discover's settings that may fix it, and Try again where [onRetry] is
 * given.
 */
@Composable
internal fun ProblemCard(
  message: UiText,
  onRetry: (() -> Unit)?,
  onSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = modifier
      .fillMaxWidth()
      .background(colors.status.failed.soft, KetchTheme.shapes.md)
      .padding(spacing.s4),
  ) {
    KetchIconImage(
      icon = KetchIcon.Warning,
      size = KetchTheme.density.navGlyph,
      tint = colors.status.failed.color,
    )
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
      Text(
        text = stringResource(Res.string.discover_error_title),
        style = KetchTheme.typography.bodyStrong,
        color = colors.textPrimary,
      )
      Text(
        text = message.resolve(),
        style = KetchTheme.typography.bodyS,
        color = colors.textSecondary,
        maxLines = 4,
        overflow = TextOverflow.Ellipsis,
      )
      Row(
        horizontalArrangement = Arrangement.spacedBy(spacing.s2),
        modifier = Modifier.padding(top = spacing.s2),
      ) {
        if (onRetry != null) {
          KetchButton(
            text = stringResource(Res.string.action_try_again),
            onClick = onRetry,
            variant = KetchButtonVariant.Secondary,
            size = KetchButtonSize.Small,
            leadingIcon = KetchIcon.Retry,
          )
        }
        KetchButton(
          text = stringResource(Res.string.discover_settings),
          onClick = onSettings,
          variant = KetchButtonVariant.Ghost,
          size = KetchButtonSize.Small,
        )
      }
    }
  }
}

/** A search the user stopped, with Try again where [onRetry] is given. */
@Composable
internal fun StoppedNote(onRetry: (() -> Unit)?, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s3),
    modifier = modifier.fillMaxWidth().heightIn(min = KetchTheme.density.buttonSmall),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s1),
    ) {
      KetchIconImage(KetchIcon.Stop, size = MarkSize, tint = colors.textTertiary)
      Text(
        text = stringResource(Res.string.discover_stopped),
        style = KetchTheme.typography.bodyS,
        color = colors.textSecondary,
      )
    }
    if (onRetry != null) {
      KetchButton(
        text = stringResource(Res.string.action_try_again),
        onClick = onRetry,
        variant = KetchButtonVariant.Secondary,
        size = KetchButtonSize.Small,
        leadingIcon = KetchIcon.Retry,
      )
    }
  }
}

/**
 * The search found nothing it trusts, with tips, and the whole web where [onSearchEverywhere]
 * is given. When the content filter hid [filtered] results, it says so instead of the tips and
 * offers Discover's settings, where the filter can be turned off.
 */
@Composable
internal fun NoResults(
  onSearchEverywhere: (() -> Unit)?,
  filtered: Int,
  onSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = modifier
      .fillMaxWidth()
      .background(colors.surfaceSunken, KetchTheme.shapes.md)
      .padding(spacing.s4),
  ) {
    KetchIconImage(KetchIcon.Search, size = KetchTheme.density.navGlyph, tint = colors.textTertiary)
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
      Text(
        text = stringResource(Res.string.discover_none_title),
        style = KetchTheme.typography.bodyStrong,
        color = colors.textPrimary,
      )
      Text(
        text = if (filtered > 0) {
          pluralStringResource(Res.plurals.discover_none_filtered, filtered, filtered)
        } else {
          stringResource(Res.string.discover_none_body)
        },
        style = KetchTheme.typography.bodyS,
        color = colors.textSecondary,
      )
      if (onSearchEverywhere != null || filtered > 0) {
        Row(
          horizontalArrangement = Arrangement.spacedBy(spacing.s2),
          modifier = Modifier.padding(top = spacing.s2),
        ) {
          if (onSearchEverywhere != null) {
            KetchButton(
              text = stringResource(Res.string.discover_search_everywhere),
              onClick = onSearchEverywhere,
              variant = KetchButtonVariant.Secondary,
              size = KetchButtonSize.Small,
              leadingIcon = KetchIcon.Search,
            )
          }
          if (filtered > 0) {
            KetchButton(
              text = stringResource(Res.string.discover_settings),
              onClick = onSettings,
              variant = KetchButtonVariant.Secondary,
              size = KetchButtonSize.Small,
              leadingIcon = KetchIcon.Settings,
            )
          }
        }
      }
    }
  }
}

/** Size of the small glyphs that lead a caption, such as a step's check. */
internal val MarkSize: Dp = 12.dp

// The share of the column a message's bubble may take.
private const val BUBBLE_SHARE = 0.8f

// Lines of the agent's reply shown before Show more.
private const val SUMMARY_LINES = 6
