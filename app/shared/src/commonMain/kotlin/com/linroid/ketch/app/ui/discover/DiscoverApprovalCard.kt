package com.linroid.ketch.app.ui.discover

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.api.log.redactUrl
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.ketchClickable
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.rememberTileShape
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.state.AiPageKind
import com.linroid.ketch.app.state.AiPageRequest
import com.linroid.ketch.app.state.PageAccessChoice
import com.linroid.ketch.app.state.PageApproval
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import com.linroid.ketch.app.ui.inspector.TextLink
import com.linroid.ketch.config.PageAccessMode
import com.linroid.ketch.config.SiteNames
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.discover_approval_allow
import ketch.app.shared.generated.resources.discover_approval_allow_all
import ketch.app.shared.generated.resources.discover_approval_allow_caption
import ketch.app.shared.generated.resources.discover_approval_allow_once
import ketch.app.shared.generated.resources.discover_approval_allow_site
import ketch.app.shared.generated.resources.discover_approval_always
import ketch.app.shared.generated.resources.discover_approval_deny
import ketch.app.shared.generated.resources.discover_approval_file
import ketch.app.shared.generated.resources.discover_approval_jump
import ketch.app.shared.generated.resources.discover_approval_page
import ketch.app.shared.generated.resources.discover_approval_pane
import ketch.app.shared.generated.resources.discover_approval_reason
import ketch.app.shared.generated.resources.discover_approval_redirect
import ketch.app.shared.generated.resources.discover_approval_redirect_from
import ketch.app.shared.generated.resources.discover_approval_settings
import org.jetbrains.compose.resources.stringResource

/**
 * Asks whether Discover may open a website: "Open download.blender.org?", the link (shortened
 * in the middle, never in its host), why the agent says it needs it, and the answers [mode]
 * offers, as [approvalChoices] lists them. Asking for each new site, Allow lets the site and its
 * subdomains in for the rest of the chat; asking every time, Allow once lets this request in,
 * and allowing the site for the chat is one of the quieter answers. The agent's reason is its
 * own words, which a page it read may have shaped, so it shows only as "Discover says: …",
 * never on a button.
 *
 * On a wide column everything lines up with the title, beside the shield, and the quieter
 * answers follow Allow and Deny on their line. On a [narrow] one the card takes its whole width:
 * what Allow does sits right under Allow and Deny, and the quieter answers stack below it.
 *
 * The card announces itself to screen readers as it appears. ⌘↩ answers with the first button
 * and ⌘⌫ denies, as their tooltips say on pointer devices.
 *
 * @param focusRequester moves the keyboard to the first button.
 */
@Composable
internal fun ApprovalCard(
  approval: PageApproval,
  mode: PageAccessMode,
  narrow: Boolean,
  focusRequester: FocusRequester,
  onAnswer: (PageAccessChoice) -> Unit,
  onSettings: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val request = approval.request
  val pane = stringResource(Res.string.discover_approval_pane, request.host)
  val answers = ApprovalAnswers(
    request = request,
    choices = approvalChoices(mode),
    focusRequester = focusRequester,
    onAnswer = onAnswer,
    onSettings = onSettings,
  )
  Column(
    verticalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = modifier
      .fillMaxWidth()
      .semantics {
        liveRegion = LiveRegionMode.Polite
        paneTitle = pane
      }
      .ketchSurface(
        level = KetchElevationLevel.E1,
        shape = KetchTheme.shapes.lg,
        fill = colors.surface,
        border = colors.accent,
      )
      .padding(spacing.s4),
  ) {
    if (narrow) {
      Row(horizontalArrangement = Arrangement.spacedBy(spacing.s3)) {
        ShieldTile()
        RequestHeading(request, reason = false, modifier = Modifier.weight(1f))
      }
      Reason(request.reason)
      answers.Stacked()
    } else {
      Row(horizontalArrangement = Arrangement.spacedBy(spacing.s3)) {
        ShieldTile()
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(spacing.s3)) {
          RequestHeading(request, reason = true)
          answers.Inline()
        }
      }
    }
  }
}

/**
 * The card's title, "Check a file on mirror.example.edu?", over the link, the website a redirect
 * came from, which is what tells a trusted site's redirect from an unknown mirror's, and, with
 * [reason], what the agent says it needs it for.
 */
@Composable
private fun RequestHeading(request: AiPageRequest, reason: Boolean, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  val title = when {
    request.redirectFrom.isNotBlank() -> Res.string.discover_approval_redirect
    request.kind == AiPageKind.FileInfo -> Res.string.discover_approval_file
    else -> Res.string.discover_approval_page
  }
  Column(modifier, verticalArrangement = Arrangement.spacedBy(KetchTheme.spacing.s0_5)) {
    Text(
      text = stringResource(title, request.host),
      style = type.bodyStrong,
      color = colors.textPrimary,
    )
    UrlText(redactUrl(request.url))
    if (request.redirectFrom.isNotBlank()) {
      // Whole, as the title's host is.
      Text(
        text = stringResource(Res.string.discover_approval_redirect_from, request.redirectFrom),
        style = type.bodyS,
        color = colors.textSecondary,
      )
    }
    if (reason) Reason(request.reason, Modifier.padding(top = KetchTheme.spacing.s1))
  }
}

/** "Discover says: …", at most two lines, when the agent gave a reason. */
@Composable
private fun Reason(reason: String, modifier: Modifier = Modifier) {
  if (reason.isBlank()) return
  Text(
    text = stringResource(Res.string.discover_approval_reason, reason),
    style = KetchTheme.typography.bodyS,
    color = KetchTheme.colors.textSecondary,
    maxLines = REASON_LINES,
    overflow = TextOverflow.Ellipsis,
    modifier = modifier,
  )
}

/**
 * [url] on one line in mono, shortened as [shortenUrl] does when it does not fit, so its scheme
 * and host always show whole.
 */
@Composable
private fun UrlText(url: String) {
  val style = KetchTheme.typography.monoS.copy(color = KetchTheme.colors.textSecondary)
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val measurer = rememberTextMeasurer()
    val width = constraints.maxWidth
    // The measurer changes with the density and the fonts, which change what fits.
    val shown = remember(url, width, style, measurer) {
      shortenUrl(url) { measurer.measure(it, style, maxLines = 1).size.width <= width }
    }
    // A host wider than the line wraps rather than lose its end.
    Text(text = shown, style = style, maxLines = REASON_LINES, overflow = TextOverflow.Ellipsis)
  }
}

/**
 * [url] as it [fits] on a line: whole, else without its query ("…?…"), else with the middle of
 * its path cut out too, as in "https://mirror.example.edu/blen…macos-arm64.dmg?…", so the file
 * it ends in still shows. The scheme and host are never cut; when they alone do not fit, the
 * rest gives way to an ellipsis.
 */
internal fun shortenUrl(url: String, fits: (String) -> Boolean): String {
  if (fits(url)) return url
  val hostStart = url.indexOf("://").let { if (it < 0) 0 else it + SCHEME_MARK }
  val pathStart = url.indexOfAny(charArrayOf('/', '?', '#'), hostStart).let {
    if (it < 0) url.length else it
  }
  val queryStart = url.indexOfAny(charArrayOf('?', '#'), pathStart).let {
    if (it < 0) url.length else it
  }
  val head = url.substring(0, pathStart)
  val path = url.substring(pathStart, queryStart)
  val query = if (queryStart < url.length) url[queryStart] + ELLIPSIS else ""
  if (path.isEmpty() && query.isEmpty()) return url
  if (fits(head + path + query)) return head + path + query
  if (!fits(head + ELLIPSIS + query)) return head + ELLIPSIS
  fun kept(count: Int): String {
    val tail = count * 2 / 3
    return head + path.take(count - tail) + ELLIPSIS + path.takeLast(tail) + query
  }
  var low = 0
  var high = path.length - 1
  while (low < high) {
    val mid = (low + high + 1) / 2
    if (fits(kept(mid))) low = mid else high = mid - 1
  }
  return kept(low)
}

/**
 * The answers a request to open a website offers, by the page access mode.
 *
 * @property primary the first button's answer, which ⌘↩ gives too: Allow, which lets the site
 *   in for the chat, or Allow once while every request asks.
 * @property quiet the answers less common than the first button and Deny, in the order shown.
 */
internal data class ApprovalChoices(
  val primary: PageAccessChoice,
  val quiet: List<PageAccessChoice>,
)

/**
 * What a request offers in [mode]. Asking for each new site, Allow lets the site in for the
 * chat, then Always allow and Allow all sites; asking every time, Allow once comes first and
 * allowing the site for the chat joins the quieter answers.
 */
internal fun approvalChoices(mode: PageAccessMode): ApprovalChoices = when (mode) {
  PageAccessMode.AskEveryTime -> ApprovalChoices(
    primary = PageAccessChoice.AllowOnce,
    quiet = listOf(
      PageAccessChoice.AllowSite,
      PageAccessChoice.AlwaysAllow,
      PageAccessChoice.AllowAll,
    ),
  )
  // Allow only shows a request that waited while the mode changed; it reads as asking per site.
  PageAccessMode.AskPerSite, PageAccessMode.Allow -> ApprovalChoices(
    primary = PageAccessChoice.AllowSite,
    quiet = listOf(PageAccessChoice.AlwaysAllow, PageAccessChoice.AllowAll),
  )
}

/** The answers of [choices] for a request, laid out in a line or stacked. */
private class ApprovalAnswers(
  val request: AiPageRequest,
  val choices: ApprovalChoices,
  val focusRequester: FocusRequester,
  val onAnswer: (PageAccessChoice) -> Unit,
  val onSettings: () -> Unit,
) {
  private val site = SiteNames.normalize(request.host).ifEmpty { request.host }

  /** Allow, Deny and the quieter answers on one line, over what Allow does and the settings. */
  @Composable
  fun Inline() {
    val spacing = KetchTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
        verticalArrangement = Arrangement.spacedBy(spacing.s1),
        itemVerticalAlignment = Alignment.CenterVertically,
      ) {
        Buttons()
        QuietAnswers()
      }
      FlowRow(
        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
        verticalArrangement = Arrangement.spacedBy(spacing.s1),
        itemVerticalAlignment = Alignment.CenterVertically,
      ) {
        AllowCaption()
        SettingsLink()
      }
    }
  }

  /**
   * Allow and Deny with what Allow does under them, then a line per quieter answer and the
   * settings, for a column too narrow to keep them on one line.
   */
  @Composable
  fun Stacked() {
    val spacing = KetchTheme.spacing
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s2)) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s3),
      ) {
        Buttons()
      }
      AllowCaption()
      Column {
        QuietAnswers()
        Box(
          contentAlignment = Alignment.CenterStart,
          modifier = Modifier.heightIn(min = KetchTheme.density.buttonSmall),
        ) {
          SettingsLink()
        }
      }
    }
  }

  @Composable
  private fun Buttons() {
    val pointer = KetchTheme.density == KetchDensity.Compact
    val primary = choices.primary
    KetchButton(
      text = stringResource(
        if (primary == PageAccessChoice.AllowOnce) {
          Res.string.discover_approval_allow_once
        } else {
          Res.string.discover_approval_allow
        },
      ),
      onClick = { onAnswer(primary) },
      size = KetchButtonSize.Small,
      shortcut = if (pointer) KetchCommands.DiscoverAllow.shortcutLabel() else null,
      modifier = Modifier.focusRequester(focusRequester),
    )
    KetchButton(
      text = stringResource(Res.string.discover_approval_deny),
      onClick = { onAnswer(PageAccessChoice.Deny) },
      variant = KetchButtonVariant.Secondary,
      size = KetchButtonSize.Small,
      shortcut = if (pointer) KetchCommands.DiscoverDeny.shortcutLabel() else null,
      modifier = Modifier.padding(end = KetchTheme.spacing.s1),
    )
  }

  @Composable
  private fun QuietAnswers() {
    for (choice in choices.quiet) {
      val label = when (choice) {
        PageAccessChoice.AllowSite -> stringResource(Res.string.discover_approval_allow_site, site)
        PageAccessChoice.AlwaysAllow -> stringResource(Res.string.discover_approval_always, site)
        PageAccessChoice.AllowAll -> stringResource(Res.string.discover_approval_allow_all)
        PageAccessChoice.AllowOnce -> stringResource(Res.string.discover_approval_allow_once)
        PageAccessChoice.Deny -> stringResource(Res.string.discover_approval_deny)
      }
      QuietAnswer(label) { onAnswer(choice) }
    }
  }

  @Composable
  private fun SettingsLink() {
    TextLink(text = stringResource(Res.string.discover_approval_settings), onClick = onSettings)
  }

  /** What Allow does when it lets the site in for the chat; Allow once needs no words. */
  @Composable
  private fun AllowCaption() {
    if (choices.primary != PageAccessChoice.AllowSite) return
    Text(
      text = stringResource(Res.string.discover_approval_allow_caption, site),
      style = KetchTheme.typography.caption,
      color = KetchTheme.colors.textTertiary,
    )
  }
}

/**
 * An answer less common than Allow and Deny, such as Always allow blender.org: accent text as
 * tall as a small button, whose words line up with the card's.
 */
@Composable
private fun QuietAnswer(text: String, onClick: () -> Unit) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.xs
  val focus = rememberFocusVisibility()
  Box(
    contentAlignment = Alignment.CenterStart,
    modifier = Modifier
      .heightIn(min = KetchTheme.density.buttonSmall)
      .focusRing(focus.visible, shape, colors.focusRing)
      .clip(shape)
      .trackFocusVisibility(focus)
      .clickable(role = Role.Button, onClick = onClick)
      // An answer that wraps, as one naming a long site does, keeps clear of the next.
      .padding(vertical = KetchTheme.spacing.s1),
  ) {
    Text(text = text, style = KetchTheme.typography.label, color = colors.accentText)
  }
}

/** The shield glyph on a soft accent tile that leads the card. */
@Composable
private fun ShieldTile() {
  val colors = KetchTheme.colors
  // About as tall as the title and the link beside it.
  val size = KetchTheme.density.buttonLarge.coerceAtMost(KetchTheme.spacing.s10)
  Box(
    contentAlignment = Alignment.Center,
    modifier = Modifier
      .size(size)
      // Rounded as the file tiles of the results are.
      .background(colors.accentSoft, rememberTileShape(size)),
  ) {
    KetchIconImage(
      icon = KetchIcon.Shield,
      size = KetchTheme.density.navGlyph,
      tint = colors.accentText,
    )
  }
}

/**
 * "Needs your OK ↓", floating over the chat while the request it waits on is out of view;
 * clicking it scrolls to the card.
 */
@Composable
internal fun ApprovalJump(onClick: () -> Unit, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.full
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focus = rememberFocusVisibility()
  val glyph = KetchTheme.density.controlGlyph
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.iconLabelGap),
    modifier = modifier
      .focusRing(focus.visible, shape, colors.focusRing)
      .ketchSurface(KetchElevationLevel.E3, shape, colors.accent)
      .background(overlay)
      .ketchClickable(interactions, focus, onClick = onClick)
      .heightIn(min = KetchTheme.density.buttonMedium)
      .padding(horizontal = spacing.s4),
  ) {
    KetchIconImage(KetchIcon.Shield, size = glyph, tint = colors.onAccent)
    Text(
      text = stringResource(Res.string.discover_approval_jump),
      style = KetchTheme.typography.label,
      color = colors.onAccent,
    )
    KetchIconImage(KetchIcon.ChevronDown, size = glyph, tint = colors.onAccent)
  }
}

// Lines of the agent's reason before it is cut, and of a link whose host is wider than the card.
private const val REASON_LINES = 2

// Length of "://", which ends a link's scheme.
private const val SCHEME_MARK = 3
private const val ELLIPSIS = "…"
