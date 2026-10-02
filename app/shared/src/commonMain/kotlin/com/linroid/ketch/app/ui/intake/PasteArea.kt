package com.linroid.ketch.app.ui.intake

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import com.linroid.ketch.api.DownloadRequest
import com.linroid.ketch.app.components.KetchButtonSize
import com.linroid.ketch.app.components.KetchIconButton
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.input.ShortcutMatcher
import com.linroid.ketch.app.platform.isMobilePlatform
import com.linroid.ketch.app.state.IntakeMode
import com.linroid.ketch.app.state.IntakeSession
import com.linroid.ketch.app.state.catchingUnlessCancelled
import com.linroid.ketch.app.state.middleEllipsis
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.downloads.ClipboardLink
import com.linroid.ketch.app.util.displayName
import com.linroid.ketch.config.ClipboardMode

/**
 * The add sheet's input: a roomy area to paste or type links, magnets and cURL commands into,
 * one per line, and to drop files and links on. Text is set in the body font; a cURL command
 * switches it to the monospace style.
 *
 * Its toolbar offers a link found on the clipboard while the area is empty ("Paste
 * ubuntu-24.04.iso from clipboard"), says where the text came from and what it holds, and has
 * Paste and Open .torrent file buttons. While something is dragged over the sheet, [dropping],
 * the area takes the accent border on a soft accent fill.
 */
@Composable
internal fun PasteArea(
  actions: IntakeActions,
  matcher: ShortcutMatcher,
  phone: Boolean,
  clipboardLink: ClipboardLink,
  dropping: Boolean,
) {
  val session = actions.session
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  val motion = KetchTheme.motion
  val shape = KetchTheme.shapes.md
  val focus = remember { FocusRequester() }
  var focused by remember { mutableStateOf(false) }
  val interactions = remember { MutableInteractionSource() }
  val hovered by interactions.collectIsHoveredAsState()
  LaunchedEffect(session) {
    // On phones a prefilled sheet keeps the keyboard down, so its rows stay in view.
    val focusInput = session.mode == IntakeMode.Add && (!phone || session.text.text.isEmpty())
    if (focusInput) catchingUnlessCancelled { focus.requestFocus() }
  }
  val empty = session.text.text.isEmpty()
  val textStyle = (if (session.isCurl) type.mono else type.body).copy(color = colors.textPrimary)
  val border by animateColorAsState(
    targetValue = when {
      dropping || focused -> colors.accent
      hovered -> colors.textTertiary
      else -> colors.borderStrong
    },
    animationSpec = tween(motion.micro),
  )
  val fill by animateColorAsState(
    targetValue = if (dropping) colors.accentSoft else colors.surfaceSunken,
    animationSpec = tween(motion.short),
  )
  val offer = clipboardOffer(session.clipboardMode, clipboardLink, short = phone)
    ?.takeIf { session.offersClipboard(it.hash) }
  val note = pasteNote(session, offer)
  val textField = @Composable {
    BasicTextField(
      value = session.text,
      onValueChange = session::onTextChange,
      textStyle = textStyle,
      cursorBrush = SolidColor(colors.accent),
      minLines = if (empty) IntakeSheetDefaults.EMPTY_INPUT_LINES else 1,
      maxLines = if (session.entries.size > 1) {
        IntakeSheetDefaults.INPUT_LINES_WITH_ROWS
      } else {
        IntakeSheetDefaults.MAX_INPUT_LINES
      },
      modifier = Modifier
        .fillMaxWidth()
        .focusRequester(focus)
        .onFocusChanged { focused = it.isFocused }
        .onPreviewKeyEvent { event -> handleInputKey(event, matcher, actions) },
      decorationBox = { inner ->
        Box {
          if (empty) {
            Text(
              text = when {
                dropping -> "Drop to add"
                phone -> "Paste links or magnets"
                else -> "Paste links or magnets — or drop a .torrent file"
              },
              style = type.body,
              color = if (dropping) colors.accentText else colors.textTertiary,
            )
          }
          inner()
        }
      },
    )
  }
  val field = @Composable { fieldModifier: Modifier ->
    if (phone) {
      // A touch tooltip would take the long press that selects text.
      Box(fieldModifier) { textField() }
    } else {
      KetchTooltip(
        text = KetchCommands.IntakeNewLine.label,
        shortcut = KetchCommands.IntakeNewLine.shortcutLabel(),
        // Typing a link and pressing ↩ adds it; the chord for another line shows on hover.
        enabled = !empty,
        modifier = fieldModifier,
        content = textField,
      )
    }
  }
  Column(
    modifier = Modifier
      .fillMaxWidth()
      .focusRing(
        visible = focused && !dropping,
        shape = shape,
        color = colors.accent.copy(alpha = IntakeSheetDefaults.FOCUS_RING_ALPHA),
        gap = IntakeSheetDefaults.NoGap,
        width = IntakeSheetDefaults.FocusRingWidth,
      )
      .background(fill, shape)
      .border(IntakeSheetDefaults.Hairline, border, shape)
      .hoverable(interactions)
      // A click beside the text puts the cursor in it.
      .pointerInput(focus) { detectTapGestures { focus.requestFocus() } }
      .animateContentSize(tween(motion.medium, easing = motion.easeStandard))
      .padding(top = spacing.s2, bottom = spacing.s1),
  ) {
    if (note == null) {
      // Nothing to say about the text: the buttons sit at the end of its last line.
      Row(verticalAlignment = Alignment.Bottom) {
        field(
          Modifier
            .weight(1f)
            .padding(start = spacing.s3, top = spacing.s1, bottom = IntakeSheetDefaults.LineInset),
        )
        PasteButtons(actions, paste = true, Modifier.padding(start = spacing.s2, end = spacing.s1))
      }
    } else {
      field(Modifier.padding(horizontal = spacing.s3, vertical = spacing.s1))
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(spacing.s1),
        modifier = Modifier
          .fillMaxWidth()
          .heightIn(min = KetchTheme.density.iconButtonTarget)
          .padding(start = spacing.s2, end = spacing.s1),
      ) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(spacing.s2),
          modifier = Modifier.weight(1f),
        ) {
          when (note) {
            is PasteNote.Offer -> ClipboardOfferChip(note.label, onClick = actions::pasteClipboard)
            is PasteNote.Text -> TextNote(note, actions)
          }
        }
        // The clipboard chip pastes already.
        PasteButtons(actions, paste = note !is PasteNote.Offer)
      }
    }
  }
}

/** What the [PasteArea]'s bottom line says about its text. */
private sealed interface PasteNote {
  /** The empty area offers the clipboard. */
  class Offer(val label: String) : PasteNote

  /** "From clipboard ✕", and a batch's summary or that the text is a cURL command. */
  class Text(val fromClipboard: Boolean, val summary: String?) : PasteNote
}

private fun pasteNote(session: IntakeSession, offer: ClipboardOffer?): PasteNote? {
  if (offer != null) return PasteNote.Offer(offer.label)
  val batch = session.entries.size > 1 || (session.entries.singleOrNull()?.linkCount ?: 1) > 1
  val summary = when {
    batch -> session.summary.text
    session.isCurl -> "cURL command"
    else -> null
  }
  if (!session.fromClipboard && summary == null) return null
  return PasteNote.Text(session.fromClipboard, summary)
}

/** Paste and Open .torrent file, which only a sheet that adds downloads offers. */
@Composable
private fun PasteButtons(actions: IntakeActions, paste: Boolean, modifier: Modifier = Modifier) {
  val session = actions.session
  if (session.mode != IntakeMode.Add) return
  Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
    // The clipboard is read only when the user asks, unless the setting ignores it.
    if (paste && session.clipboardMode != ClipboardMode.Off) {
      KetchIconButton(
        icon = KetchIcon.Paste,
        contentDescription = "Paste from clipboard",
        shortcut = KetchCommands.PasteLinks.shortcutLabel(),
        size = KetchButtonSize.Small,
        onClick = actions::paste,
      )
    }
    KetchIconButton(
      icon = KetchIcon.FileTorrent,
      contentDescription = "Open .torrent file",
      shortcut = KetchCommands.IntakeOpenTorrent.shortcutLabel(),
      size = KetchButtonSize.Small,
      onClick = actions::pickTorrents,
    )
  }
}

/** "From clipboard ✕", then a batch's summary or that the text is a cURL command. */
@Composable
private fun TextNote(note: PasteNote.Text, actions: IntakeActions) {
  val colors = KetchTheme.colors
  val type = KetchTheme.typography
  if (note.fromClipboard) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = "From clipboard",
        style = type.caption,
        color = colors.textSecondary,
        maxLines = 1,
        modifier = Modifier.padding(start = KetchTheme.spacing.s1),
      )
      KetchIconButton(
        icon = KetchIcon.Close,
        contentDescription = "Clear the text from the clipboard",
        size = KetchButtonSize.Small,
        onClick = actions.session::dismissClipboard,
      )
    }
  }
  if (note.summary != null) {
    Text(
      text = note.summary,
      style = type.caption,
      color = colors.textSecondary,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      modifier = if (note.fromClipboard) {
        Modifier
      } else {
        Modifier.padding(start = KetchTheme.spacing.s1)
      },
    )
  }
}

/** What the clipboard chip offers: its text and the clip's hash, when known. */
internal data class ClipboardOffer(val label: String, val hash: String?)

/**
 * The chip the empty sheet shows for [link]: "Paste ubuntu-24.04.iso from clipboard" for a link
 * read without a notice, or just "Paste ubuntu-24.04.iso" when [short]; "Paste copied link"
 * where only a phone's system can tell one is there; nothing when the clipboard [mode] is off or
 * the platform cannot tell.
 */
internal fun clipboardOffer(
  mode: ClipboardMode,
  link: ClipboardLink,
  short: Boolean = false,
): ClipboardOffer? =
  when (link) {
    ClipboardLink.None -> null
    is ClipboardLink.Found -> {
      val name = middleEllipsis(displayName(DownloadRequest(url = link.url)), MAX_OFFER_NAME)
      ClipboardOffer(if (short) "Paste $name" else "Paste $name from clipboard", link.hash)
    }
    // macOS calls any text a link, and a filled sheet already read it there.
    ClipboardLink.Maybe -> ClipboardOffer("Paste copied link", hash = null)
      .takeIf { mode == ClipboardMode.Suggest && isMobilePlatform }
  }.takeIf { mode != ClipboardMode.Off }

/** The accent chip that pastes the clipboard into the empty area. */
@Composable
private fun ClipboardOfferChip(label: String, onClick: () -> Unit) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.full
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val focusVisibility = rememberFocusVisibility()
  Row(
    verticalAlignment = Alignment.CenterVertically,
    horizontalArrangement = Arrangement.spacedBy(spacing.s1),
    modifier = Modifier
      .focusRing(focusVisibility.visible, shape, colors.focusRing)
      .height(KetchTheme.density.chip)
      .clip(shape)
      .background(colors.accentSoft)
      .background(overlay)
      .trackFocusVisibility(focusVisibility)
      .clickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClick = onClick,
      )
      .padding(horizontal = spacing.s3),
  ) {
    KetchIconImage(
      icon = KetchIcon.Paste,
      size = KetchTheme.density.controlGlyph,
      tint = colors.accentText,
    )
    Text(
      text = label,
      style = KetchTheme.typography.labelS,
      color = colors.accentText,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** Longest file name the clipboard chip shows before shortening it in the middle. */
private const val MAX_OFFER_NAME = 32
