package com.linroid.ketch.app.ui.discover

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchBadge
import com.linroid.ketch.app.components.KetchBadgeTone
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchCheckbox
import com.linroid.ketch.app.components.KetchFileTypeChip
import com.linroid.ketch.app.components.KetchFileTypeChipDefaults
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.KetchTriStateCheckbox
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.i18n.SEPARATOR
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.percentText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.sizeText
import com.linroid.ketch.app.i18n.verbatim
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.state.AiDiscoverDraft
import com.linroid.ketch.app.state.AiDiscoverState
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.SettingsTarget
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.list.FileNameText
import com.linroid.ketch.app.util.urlHost
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_try_again
import ketch.app.shared.generated.resources.discover_clear_selection
import ketch.app.shared.generated.resources.discover_error_title
import ketch.app.shared.generated.resources.discover_match
import ketch.app.shared.generated.resources.discover_match_tooltip
import ketch.app.shared.generated.resources.discover_none_body
import ketch.app.shared.generated.resources.discover_none_title
import ketch.app.shared.generated.resources.discover_not_encrypted
import ketch.app.shared.generated.resources.discover_results
import ketch.app.shared.generated.resources.discover_results_for
import ketch.app.shared.generated.resources.discover_search_everywhere
import ketch.app.shared.generated.resources.discover_select_all
import ketch.app.shared.generated.resources.discover_settings
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * What a search shows under the search field: the agent's steps, then placeholder rows while it
 * runs, the downloads it found with a select-all row, a page saying it found none, or what went
 * wrong.
 *
 * @param inset where the page's content starts, so rows line up with the search field.
 * @param narrow fits the rows to a phone: smaller tiles, with how sure the agent is in the line
 *   under the name.
 */
@Composable
internal fun DiscoverResults(
  state: AppState,
  discover: AiDiscoverState,
  inset: Dp,
  narrow: Boolean,
  modifier: Modifier = Modifier,
) {
  val controller = state.aiDiscover
  val draft = controller.draft
  val spacing = KetchTheme.spacing
  // Rows reach a little past the page's edge, so their highlight frames the content.
  val content = Modifier.padding(horizontal = inset - spacing.s2)
  LazyColumn(
    contentPadding = PaddingValues(start = spacing.s2, end = spacing.s2, bottom = spacing.s4),
    verticalArrangement = Arrangement.spacedBy(spacing.s0_5),
    modifier = modifier.fillMaxWidth(),
  ) {
    item(key = "steps") {
      DiscoverSteps(
        steps = controller.steps,
        running = discover == AiDiscoverState.Loading,
        modifier = content.padding(bottom = spacing.s3),
      )
    }
    when (discover) {
      AiDiscoverState.Idle -> Unit
      AiDiscoverState.Loading -> items(SKELETON_ROWS) { index ->
        SkeletonRow(index, narrow, content)
      }
      is AiDiscoverState.Error -> item(key = "error") {
        ProblemCard(
          message = discover.message,
          onRetry = { controller.retry() },
          onSettings = { state.openSettings(SettingsTarget(SettingsTarget.Page.Discover)) },
          modifier = content,
        )
      }
      is AiDiscoverState.Results -> {
        val candidates = discover.candidates.distinctBy { it.url }
        if (candidates.isEmpty()) {
          item(key = "none") {
            NoResults(
              draft = draft,
              onSearchEverywhere = {
                draft.sites = ""
                draft.showSites = false
                controller.retry()
              },
              modifier = content,
            )
          }
        } else {
          item(key = "header") { ResultsHeader(draft, candidates, content) }
          items(candidates, key = { it.url }) { candidate ->
            ResultRow(
              candidate = candidate,
              selected = candidate.url in draft.selected,
              onToggle = { draft.toggle(candidate) },
              padding = inset - spacing.s2,
              narrow = narrow,
            )
          }
        }
      }
    }
  }
}

/** "4 downloads for “…”", with the box that selects or clears every result. */
@Composable
private fun ResultsHeader(
  draft: AiDiscoverDraft,
  candidates: List<AiCandidate>,
  modifier: Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val selected = candidates.count { it.url in draft.selected }
  val all = candidates.map { it.url }.toSet()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = modifier.fillMaxWidth().heightIn(min = KetchTheme.density.tableRow),
  ) {
    val everything = selected == candidates.size
    val toggleAll = stringResource(
      if (everything) Res.string.discover_clear_selection else Res.string.discover_select_all
    )
    KetchTriStateCheckbox(
      modifier = Modifier.checkboxSlot().semantics { contentDescription = toggleAll },
      state = when (selected) {
        0 -> ToggleableState.Off
        candidates.size -> ToggleableState.On
        else -> ToggleableState.Indeterminate
      },
      onClick = {
        draft.selected = if (everything) draft.selected - all else draft.selected + all
      },
    )
    val count = pluralStringResource(Res.plurals.discover_results, candidates.size, candidates.size)
    val query = draft.submittedQuery.takeIf { it.isNotBlank() }
      ?.let { stringResource(Res.string.discover_results_for, it) }
    Text(
      text = buildAnnotatedString {
        withStyle(SpanStyle(color = colors.textPrimary)) { append(count) }
        if (query != null) {
          withStyle(SpanStyle(color = colors.textSecondary)) { append(" $query") }
        }
      },
      style = KetchTheme.typography.bodyStrong,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = Modifier.weight(1f),
    )
  }
}

/**
 * One download Discover found: its file tile, the name it saves as, "412 MB · host" with a
 * warning for links without TLS, what the agent says it is, and how sure the agent is.
 * Clicking anywhere selects it.
 */
@Composable
private fun ResultRow(
  candidate: AiCandidate,
  selected: Boolean,
  onToggle: () -> Unit,
  padding: Dp,
  narrow: Boolean,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val shape = KetchTheme.shapes.md
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  val name = candidateName(candidate)
  val match = stringResource(
    Res.string.discover_match,
    percentText(percentOf(candidate.confidence)).resolve(),
  )
  val notEncrypted = stringResource(Res.string.discover_not_encrypted)
  val meta = candidateMeta(candidate).resolve()
  // Tertiary text is too faint on the selected fill.
  val quiet = if (selected) colors.textSecondary else colors.textTertiary
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = Modifier
      .fillMaxWidth()
      .heightIn(min = KetchTheme.density.listRow)
      .focusRing(focus.visible, shape, colors.focusRing, gap = -spacing.s0_5)
      .clip(shape)
      .background(if (selected) colors.rowSelected else colors.surface)
      .background(overlay)
      .trackFocusVisibility(focus)
      .toggleable(
        value = selected,
        interactionSource = interactions,
        indication = null,
        role = Role.Checkbox,
        onValueChange = { onToggle() },
      )
      .padding(horizontal = padding, vertical = spacing.s2),
  ) {
    KetchCheckbox(checked = selected, onCheckedChange = null, modifier = Modifier.checkboxSlot())
    KetchFileTypeChip(
      fileName = name,
      sourceUrl = candidate.url,
      mimeType = candidate.mimeType,
      size = tileSize(narrow),
    )
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s0_5)) {
      FileNameText(text = name, style = type.bodyStrong, color = colors.textPrimary)
      Text(
        text = buildAnnotatedString {
          if (narrow) {
            withStyle(SpanStyle(color = confidenceColor(candidate.confidence))) { append(match) }
            append(SEPARATOR)
          }
          // Ahead of the host, so a phone never cuts the warning off.
          if (candidate.url.startsWith("http://", ignoreCase = true)) {
            withStyle(SpanStyle(color = colors.status.paused.color)) { append(notEncrypted) }
            append(SEPARATOR)
          }
          append(meta)
        },
        style = type.caption,
        color = colors.textSecondary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
      )
      val about = candidate.description.ifBlank { candidate.title.takeIf { it != name } }
      if (!about.isNullOrBlank()) {
        Text(
          text = about,
          style = type.caption,
          color = quiet,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
        )
      }
    }
    if (!narrow) ConfidenceBadge(candidate.confidence)
  }
}

/** "92%", green when the agent is sure, amber when it is not. */
@Composable
private fun ConfidenceBadge(confidence: Float) {
  KetchTooltip(text = stringResource(Res.string.discover_match_tooltip)) {
    KetchBadge(
      text = percentText(percentOf(confidence)).resolve(),
      tone = when {
        confidence >= SURE -> KetchBadgeTone.Success
        confidence >= UNSURE -> KetchBadgeTone.Neutral
        else -> KetchBadgeTone.Warning
      },
    )
  }
}

/** The color of how sure the agent is, as the badge would show it. */
@Composable
private fun confidenceColor(confidence: Float): Color {
  val colors = KetchTheme.colors
  return when {
    confidence >= SURE -> colors.status.completed.color
    confidence >= UNSURE -> colors.textSecondary
    else -> colors.status.paused.color
  }
}

/** The file tile of a result: smaller on a phone, where the name needs the room. */
private fun tileSize(narrow: Boolean): Dp =
  if (narrow) KetchFileTypeChipDefaults.TouchSize else KetchFileTypeChipDefaults.LargeSize

private fun percentOf(confidence: Float): Int = (confidence.coerceIn(0f, 1f) * PERCENT).toInt()

/**
 * Lays a checkbox out in the room of its box alone, so it lines up with the content above it,
 * while its larger touch target spills into the row's padding.
 */
@Composable
private fun Modifier.checkboxSlot(): Modifier =
  size(checkboxSize()).wrapContentSize(unbounded = true)

/** The side of a checkbox's box, which matches a control glyph at both densities. */
@Composable
private fun checkboxSize(): Dp = KetchTheme.density.controlGlyph

/** "412 MB · download.blender.org", leaving out what is not known. */
internal fun candidateMeta(candidate: AiCandidate): UiText =
  listOfNotNull(
    candidate.fileSize?.takeIf { it > 0 }?.let(::sizeText),
    urlHost(candidate.url)?.let(::verbatim),
  ).joinText()

/** A row's shape while a search runs; it breathes unless motion is reduced. */
@Composable
private fun SkeletonRow(index: Int, narrow: Boolean, modifier: Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shapes = KetchTheme.shapes
  val alpha = if (KetchTheme.reduceMotion) {
    1f
  } else {
    val transition = rememberInfiniteTransition()
    val pulse by transition.animateFloat(
      initialValue = 1f,
      targetValue = SKELETON_DIM,
      animationSpec = infiniteRepeatable(tween(SKELETON_PULSE_MS), RepeatMode.Reverse),
    )
    pulse
  }
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = modifier
      .fillMaxWidth()
      .heightIn(min = KetchTheme.density.listRow)
      .padding(vertical = spacing.s2)
      .alpha(alpha),
  ) {
    Box(Modifier.size(checkboxSize()).background(colors.surfaceSunken, shapes.xs))
    Box(Modifier.size(tileSize(narrow)).background(colors.surfaceSunken, shapes.md))
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
      Box(
        Modifier
          .fillMaxWidth(SkeletonWidths[index % SkeletonWidths.size])
          .height(spacing.s3)
          .background(colors.surfaceSunken, shapes.xs),
      )
      Box(
        Modifier
          .fillMaxWidth(SkeletonWidths[index % SkeletonWidths.size] / 2)
          .height(spacing.s2)
          .background(colors.surfaceSunken, shapes.xs),
      )
    }
    Spacer(Modifier.width(spacing.s10))
  }
}

/** What went wrong, with Try again and the settings that may fix it. */
@Composable
private fun ProblemCard(
  message: UiText,
  onRetry: () -> Unit,
  onSettings: () -> Unit,
  modifier: Modifier,
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
        KetchButton(
          text = stringResource(Res.string.action_try_again),
          onClick = onRetry,
          variant = KetchButtonVariant.Secondary,
          size = KetchButtonSize.Small,
          leadingIcon = KetchIcon.Retry,
        )
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

/** The search found nothing it trusts; offers the whole web when websites limited it. */
@Composable
private fun NoResults(
  draft: AiDiscoverDraft,
  onSearchEverywhere: () -> Unit,
  modifier: Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val limited = draft.siteList().isNotEmpty()
  Column(
    horizontalAlignment = Alignment.CenterHorizontally,
    verticalArrangement = Arrangement.spacedBy(spacing.s2),
    modifier = modifier.fillMaxWidth().padding(vertical = spacing.s10),
  ) {
    KetchIconImage(KetchIcon.Search, size = KetchTheme.density.navGlyph, tint = colors.textTertiary)
    Text(
      text = stringResource(Res.string.discover_none_title),
      style = KetchTheme.typography.titleM,
      color = colors.textPrimary,
    )
    Text(
      text = stringResource(Res.string.discover_none_body),
      style = KetchTheme.typography.bodyS,
      color = colors.textSecondary,
      textAlign = TextAlign.Center,
      modifier = Modifier.widthIn(max = HintWidth),
    )
    if (limited) {
      KetchButton(
        text = stringResource(Res.string.discover_search_everywhere),
        onClick = onSearchEverywhere,
        variant = KetchButtonVariant.Secondary,
        size = KetchButtonSize.Small,
        modifier = Modifier.padding(top = spacing.s2),
      )
    }
  }
}

private val HintWidth: Dp = 480.dp
private val SkeletonWidths = listOf(0.62f, 0.48f, 0.56f)
private const val SKELETON_ROWS = 3
private const val SKELETON_DIM = 0.45f
private const val SKELETON_PULSE_MS = 900
private const val PERCENT = 100
private const val SURE = 0.75f
private const val UNSURE = 0.5f
