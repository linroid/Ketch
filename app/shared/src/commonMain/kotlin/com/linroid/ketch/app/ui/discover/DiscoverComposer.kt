package com.linroid.ketch.app.ui.discover

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusManager
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isMetaPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchButton
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchButtonVariant
import com.linroid.ketch.app.components.KetchChip
import com.linroid.ketch.app.components.KetchComposerField
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.CommandScope
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.ShortcutContext
import com.linroid.ketch.app.state.DiscoverDraft
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.discover_find
import ketch.app.shared.generated.resources.discover_follow_up_placeholder
import ketch.app.shared.generated.resources.discover_query_placeholder
import ketch.app.shared.generated.resources.discover_send
import ketch.app.shared.generated.resources.discover_settings
import ketch.app.shared.generated.resources.discover_setup_to_continue
import ketch.app.shared.generated.resources.discover_sites_count
import ketch.app.shared.generated.resources.discover_sites_hint
import ketch.app.shared.generated.resources.discover_sites_limit
import ketch.app.shared.generated.resources.discover_stop
import org.jetbrains.compose.resources.pluralStringResource
import org.jetbrains.compose.resources.stringResource

/**
 * The chat's composer: one rounded surface holding the message, up to six lines before it
 * scrolls, the "Limit to websites" and model chips, and Find (a chat's first message) or Send, which turns
 * into Stop while the session searches. The website field opens at the top of the surface,
 * above the message, from the chip.
 *
 * With a keyboard ↩ sends and ⇧↩ starts a line, both left to an input method while it composes,
 * and Tab moves on to the chip rather than typing a tab. With an on-screen keyboard Enter starts
 * a line, the button sends, and sending puts the keyboard away; the website field folds away as
 * the keyboard opens unless it is being typed in, and the chip opening it then moves the
 * keyboard into it.
 *
 * @param firstMessage whether the next message starts the chat.
 * @param running whether the session searches; it has to finish or stop before the next message.
 * @param touch lays it out for fingers, without key hints; it says nothing about Enter, which
 *   follows [softKeyboard].
 * @param softKeyboard whether the platform types with an on-screen keyboard, as phones and
 *   tablets do: Enter starts a line, and sending puts the keyboard away.
 * @param model the chip of the model the next message searches with, after the website chip;
 *   `null` for none.
 * @param target the device chip shown after the model chip, such as while no add bar shows
 *   one; `null` for none.
 */
@Composable
internal fun DiscoverComposer(
  draft: DiscoverDraft,
  firstMessage: Boolean,
  running: Boolean,
  touch: Boolean,
  softKeyboard: Boolean,
  keyboardVisible: Boolean,
  focus: DiscoverFocus,
  onSend: () -> Unit,
  onStop: () -> Unit,
  modifier: Modifier = Modifier,
  model: (@Composable () -> Unit)? = null,
  target: (@Composable () -> Unit)? = null,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.lg
  val focusManager = LocalFocusManager.current
  val canSend = draft.text.text.isNotBlank() && !running
  val send = {
    if (canSend) {
      onSend()
      // The keyboard goes away, so the answer has the screen.
      if (softKeyboard) focusManager.clearFocus()
    }
  }
  val focused = focus.messageFocused || focus.sitesFocused
  val border by animateColorAsState(
    targetValue = if (focused) colors.accent else colors.borderStrong,
    animationSpec = tween(KetchTheme.motion.micro),
  )
  // Folded each time the on-screen keyboard opens, unless the website field has it; the chip
  // unfolds it, and it stays open while the keyboard does.
  var folded by remember(softKeyboard, keyboardVisible) {
    mutableStateOf(softKeyboard && keyboardVisible && !focus.sitesFocused)
  }
  val sitesShown = draft.showSites && !folded
  var focusSites by remember { mutableStateOf(false) }
  LaunchedEffect(focusSites, sitesShown) {
    if (!focusSites || !sitesShown) return@LaunchedEffect
    // Once the field is composed.
    withFrameNanos {}
    runCatching { focus.sites.requestFocus() }
    focusSites = false
  }
  Column(
    modifier = modifier
      .fillMaxWidth()
      .focusRing(
        visible = focused,
        shape = shape,
        color = colors.accent.copy(alpha = RING_ALPHA),
        gap = 0.dp,
        width = RingWidth,
      )
      .ketchSurface(KetchElevationLevel.E1, shape, colors.surfaceRaised, border)
      // A tap beside the text puts the cursor in it.
      .pointerInput(focus) { detectTapGestures { focus.message.requestFocus() } },
  ) {
    AnimatedVisibility(sitesShown) {
      SitesField(draft, focus, softKeyboard, send)
    }
    KetchComposerField(
      value = draft.text,
      onValueChange = { draft.text = it },
      placeholder = stringResource(
        if (firstMessage) {
          Res.string.discover_query_placeholder
        } else {
          Res.string.discover_follow_up_placeholder
        },
      ),
      maxLines = MAX_LINES,
      keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences),
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = spacing.s4, end = spacing.s4, top = spacing.s3, bottom = spacing.s2)
        .focusRequester(focus.message)
        .onFocusChanged { focus.messageFocused = it.isFocused }
        .onPreviewKeyEvent { event ->
          val composing = draft.text.composition != null
          moveFocusOnTab(event, focusManager, composing) ||
            (!softKeyboard && sendOnEnter(event, composing, send))
        },
    )
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier
        .fillMaxWidth()
        .padding(start = spacing.s3, end = spacing.s2, bottom = spacing.s2),
    ) {
      SitesChip(
        draft = draft,
        shown = sitesShown,
        onToggle = {
          if (sitesShown) {
            draft.showSites = false
          } else {
            draft.showSites = true
            folded = false
            // The keyboard moves into the field, so it stays open while the keyboard is up.
            if (softKeyboard && keyboardVisible) focusSites = true
          }
        },
      )
      model?.invoke()
      target?.invoke()
      Spacer(Modifier.weight(1f))
      if (running) {
        KetchButton(
          text = stringResource(Res.string.discover_stop),
          onClick = onStop,
          variant = KetchButtonVariant.Secondary,
          leadingIcon = KetchIcon.Stop,
          shortcut = if (touch) null else KetchCommands.DiscoverStop.shortcutLabel(),
        )
      } else {
        KetchButton(
          text = stringResource(
            if (firstMessage) Res.string.discover_find else Res.string.discover_send,
          ),
          onClick = send,
          enabled = canSend,
          leadingIcon = KetchIcon.Send,
          shortcut = if (touch) null else KetchCommands.DiscoverSend.shortcutLabel(),
        )
      }
    }
  }
}

/**
 * In place of the composer while Discover is not set up, as for a search picked from the
 * history: "Set up Discover to continue", with Discover's settings.
 */
@Composable
internal fun SetupNotice(onSettings: () -> Unit, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s3),
    modifier = modifier
      .fillMaxWidth()
      .ketchSurface(
        level = KetchElevationLevel.E0,
        shape = KetchTheme.shapes.lg,
        fill = colors.surfaceSunken,
        border = colors.hairline,
      )
      .padding(start = spacing.s4, end = spacing.s2, top = spacing.s2, bottom = spacing.s2),
  ) {
    KetchIconImage(
      icon = KetchIcon.Info,
      size = KetchTheme.density.controlGlyph,
      tint = colors.textSecondary,
    )
    Text(
      text = stringResource(Res.string.discover_setup_to_continue),
      style = KetchTheme.typography.bodyS,
      color = colors.textSecondary,
      modifier = Modifier.weight(1f),
    )
    KetchButton(
      text = stringResource(Res.string.discover_settings),
      onClick = onSettings,
      variant = KetchButtonVariant.Secondary,
      size = KetchButtonSize.Small,
      leadingIcon = KetchIcon.Settings,
    )
  }
}

/**
 * Opens and closes the website field, [shown] or not; it reads as selected while websites limit
 * the next message.
 */
@Composable
private fun SitesChip(draft: DiscoverDraft, shown: Boolean, onToggle: () -> Unit) {
  val sites = draft.siteList()
  KetchChip(
    label = when (sites.size) {
      0 -> stringResource(Res.string.discover_sites_limit)
      1 -> sites.single()
      else -> pluralStringResource(Res.plurals.discover_sites_count, sites.size, sites.size)
    },
    selected = sites.isNotEmpty(),
    onClick = onToggle,
    leadingIcon = KetchIcon.Filter,
    trailingIcon = if (shown) KetchIcon.ChevronDown else KetchIcon.ChevronUp,
  )
}

/**
 * The websites to limit the next message to, at the top of the composer over a hairline, with
 * how they are read. ↩ sends from here too.
 */
@Composable
private fun SitesField(
  draft: DiscoverDraft,
  focus: DiscoverFocus,
  softKeyboard: Boolean,
  send: () -> Unit,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val glyph = KetchTheme.density.controlGlyph
  Column {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(spacing.s2),
      modifier = Modifier.padding(start = spacing.s4, end = spacing.s4, top = spacing.s3),
    ) {
      KetchIconImage(KetchIcon.Link, size = glyph, tint = colors.textTertiary)
      KetchComposerField(
        value = draft.sites,
        onValueChange = { draft.sites = it },
        placeholder = ExampleSites.joinToString(", "),
        textStyle = KetchTheme.typography.bodyS,
        keyboardOptions = KeyboardOptions(
          keyboardType = KeyboardType.Uri,
          imeAction = ImeAction.Done,
        ),
        modifier = Modifier
          .weight(1f)
          .focusRequester(focus.sites)
          .onFocusChanged { focus.sitesFocused = it.isFocused }
          .onPreviewKeyEvent { event ->
            !softKeyboard && sendOnEnter(event, composing = false, send)
          },
      )
    }
    Text(
      text = stringResource(Res.string.discover_sites_hint),
      style = KetchTheme.typography.caption,
      color = colors.textTertiary,
      modifier = Modifier.padding(
        start = spacing.s4 + glyph + spacing.s2,
        end = spacing.s4,
        top = spacing.s1,
        bottom = spacing.s3,
      ),
    )
    Spacer(
      Modifier
        .fillMaxWidth()
        .padding(horizontal = spacing.s3)
        .height(HairlineWidth)
        .background(colors.hairline),
    )
  }
}

/**
 * Moves the keyboard on Tab and ⇧Tab, which the multi-line message field would otherwise type as
 * a tab, never useful in a search; returns whether it took the key. An input method that is
 * [composing] keeps it.
 */
private fun moveFocusOnTab(
  event: KeyEvent,
  focusManager: FocusManager,
  composing: Boolean,
): Boolean {
  if (event.key != Key.Tab || composing) return false
  if (event.isCtrlPressed || event.isMetaPressed || event.isAltPressed) return false
  // The key-up too, so the field never sees half of the stroke.
  if (event.type != KeyEventType.KeyDown) return true
  val direction = if (event.isShiftPressed) FocusDirection.Previous else FocusDirection.Next
  focusManager.moveFocus(direction)
  return true
}

/**
 * Sends on ↩ from a keyboard, unless an input method is [composing], since that ↩ confirms the
 * composition; returns whether it took the key. ⇧↩ is left to the field, which starts a line.
 */
private fun sendOnEnter(event: KeyEvent, composing: Boolean, send: () -> Unit): Boolean {
  val context = ShortcutContext(
    overlay = CommandScope.Discover,
    textFieldFocused = true,
    composing = composing,
  )
  if (DiscoverKeys.match(event, context) != KetchCommands.DiscoverSend) return false
  send()
  return true
}

// Websites the website field shows as an example, in the comma-separated form it reads.
private val ExampleSites = listOf("ubuntu.com", "blender.org")

private val HairlineWidth: Dp = 1.dp
private val RingWidth: Dp = 3.dp
private const val RING_ALPHA = 0.2f

// Lines the message grows to before it scrolls.
private const val MAX_LINES = 6
