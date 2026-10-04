package com.linroid.ketch.app.ui.discover

import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxState
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import com.linroid.ketch.app.components.KetchBadge
import com.linroid.ketch.app.components.KetchBadgeTone
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchCheckbox
import com.linroid.ketch.app.components.KetchFileTypeChip
import com.linroid.ketch.app.components.KetchFileTypeChipDefaults
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchTooltip
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
import com.linroid.ketch.app.input.CommandScope
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.ShortcutContext
import com.linroid.ketch.app.state.AiCandidate
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.list.FileNameText
import com.linroid.ketch.app.util.urlHost
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.discover_discard
import ketch.app.shared.generated.resources.discover_match
import ketch.app.shared.generated.resources.discover_match_tooltip
import ketch.app.shared.generated.resources.discover_not_encrypted
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * One download Discover found: its file tile, the name it saves as, "412 MB · host" with a
 * warning for links without TLS, what the agent says it is, how sure the agent is, and the ✕
 * that discards it. Clicking anywhere else selects it.
 *
 * With a pointer the ✕ shows while the row is hovered or holds the focus, in a slot kept for it
 * so the badge never moves; on touch it always shows, and swiping the row to the start discards
 * it too. ⌫ (Delete off Apple keyboards) discards the focused row and moves the keyboard to the
 * next one, and screen readers offer Discard as an action.
 *
 * @param padding the row's horizontal padding, which lines its content up with the thread's.
 * @param narrow fits the row to a phone: a smaller tile, with how sure the agent is in the line
 *   under the name.
 */
@Composable
internal fun ResultRow(
  candidate: AiCandidate,
  selected: Boolean,
  onToggle: () -> Unit,
  onDiscard: () -> Unit,
  padding: Dp,
  narrow: Boolean,
  modifier: Modifier = Modifier,
) {
  val touch = KetchTheme.density == KetchDensity.Comfortable
  if (touch) {
    SwipeToDiscard(onDiscard, modifier) {
      ResultRowBody(candidate, selected, onToggle, onDiscard, padding, narrow, touch = true)
    }
  } else {
    Box(modifier) {
      ResultRowBody(candidate, selected, onToggle, onDiscard, padding, narrow, touch = false)
    }
  }
}

@Composable
private fun ResultRowBody(
  candidate: AiCandidate,
  selected: Boolean,
  onToggle: () -> Unit,
  onDiscard: () -> Unit,
  padding: Dp,
  narrow: Boolean,
  touch: Boolean,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val shape = KetchTheme.shapes.md
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val hovered by interactions.collectIsHoveredAsState()
  val focus = rememberFocusVisibility()
  var focusWithin by remember { mutableStateOf(false) }
  val discard by rememberUpdatedState(onDiscard)
  val focusManager = LocalFocusManager.current
  val name = candidateName(candidate)
  val discardLabel = stringResource(Res.string.discover_discard)
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
      .onFocusChanged { focusWithin = it.hasFocus }
      .onKeyEvent { event -> discardOnKey(event, focusManager, discard) }
      .semantics {
        customActions = listOf(
          CustomAccessibilityAction(discardLabel) {
            discard()
            true
          },
        )
      }
      .trackFocusVisibility(focus)
      .toggleable(
        value = selected,
        interactionSource = interactions,
        indication = null,
        role = Role.Checkbox,
        onValueChange = { onToggle() },
      )
      .padding(start = padding, end = padding - spacing.s1, top = spacing.s2, bottom = spacing.s2),
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
    // The ✕ keeps its slot while hidden, so the badge never moves under the pointer.
    val shown = touch || hovered || focusWithin
    val visibility by animateFloatAsState(
      targetValue = if (shown) 1f else 0f,
      animationSpec = tween(KetchTheme.motion.micro),
    )
    KetchIconButton(
      icon = KetchIcon.Close,
      onClick = onDiscard,
      size = KetchButtonSize.Small,
      tint = colors.textTertiary,
      contentDescription = discardLabel,
      shortcut = if (touch) null else KetchCommands.DiscoverDiscard.shortcutLabel(),
      modifier = Modifier.alpha(visibility),
    )
  }
}

/**
 * Runs [remove] when [event] is Discover's ⌫ (Delete off Apple keyboards) on a focused row, and
 * first moves the keyboard to the row below, or above when none is, so it never falls out of
 * the list with the row; returns whether it took the key. History rows use it too.
 */
internal fun discardOnKey(
  event: KeyEvent,
  focusManager: FocusManager,
  remove: () -> Unit,
): Boolean {
  val command = DiscoverKeys.match(event, ShortcutContext(overlay = CommandScope.Discover))
  if (command != KetchCommands.DiscoverDiscard) return false
  if (!focusManager.moveFocus(FocusDirection.Down)) focusManager.moveFocus(FocusDirection.Up)
  remove()
  return true
}

/**
 * Lets a touch row be swiped to the start to discard it, past [SWIPE_THRESHOLD] of its width or
 * with a fling, over the failed color with the ✕. The row springs back, and the thread drops it.
 *
 * Where the row was swiped to is not saved: the thread drops the row the frame after the swipe,
 * before it springs back, and a saved swipe would come back with Undo and discard it again.
 */
@Composable
private fun SwipeToDiscard(
  onDiscard: () -> Unit,
  modifier: Modifier,
  content: @Composable () -> Unit,
) {
  val colors = KetchTheme.colors
  val swipe = remember {
    SwipeToDismissBoxState(
      initialValue = SwipeToDismissBoxValue.Settled,
      positionalThreshold = { it * SWIPE_THRESHOLD },
    )
  }
  val scope = rememberCoroutineScope()
  SwipeToDismissBox(
    state = swipe,
    enableDismissFromStartToEnd = false,
    onDismiss = { direction ->
      if (direction == SwipeToDismissBoxValue.EndToStart) onDiscard()
      scope.launch { swipe.reset() }
    },
    backgroundContent = {
      val swiping = swipe.dismissDirection == SwipeToDismissBoxValue.EndToStart
      Box(
        contentAlignment = Alignment.CenterEnd,
        modifier = Modifier
          .fillMaxSize()
          .clip(KetchTheme.shapes.md)
          .background(if (swiping) colors.status.failed.soft else Color.Transparent)
          .padding(horizontal = KetchTheme.spacing.s6),
      ) {
        if (swiping) {
          KetchIconImage(
            icon = KetchIcon.Close,
            size = KetchTheme.density.controlGlyph,
            tint = colors.status.failed.color,
          )
        }
      }
    },
    modifier = modifier,
  ) {
    content()
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

/** A row's shape while a search runs; it breathes unless motion is reduced. */
@Composable
internal fun SkeletonRow(index: Int, narrow: Boolean, modifier: Modifier = Modifier) {
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
  val width = SkeletonWidths[index % SkeletonWidths.size]
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
          .fillMaxWidth(width)
          .height(spacing.s3)
          .background(colors.surfaceSunken, shapes.xs),
      )
      Box(
        Modifier
          .fillMaxWidth(width / 2)
          .height(spacing.s2)
          .background(colors.surfaceSunken, shapes.xs),
      )
    }
    Spacer(Modifier.width(spacing.s10))
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
internal fun Modifier.checkboxSlot(): Modifier =
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

/**
 * Share of a touch row's width a slow swipe must cross to discard it, as on the Downloads list,
 * so a sideways drift while scrolling never discards a row.
 */
private const val SWIPE_THRESHOLD = 0.4f
private val SkeletonWidths = listOf(0.62f, 0.48f, 0.56f)
private const val SKELETON_DIM = 0.45f
private const val SKELETON_PULSE_MS = 900
private const val PERCENT = 100
private const val SURE = 0.75f
private const val UNSURE = 0.5f
