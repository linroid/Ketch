package com.linroid.ketch.app.ui.shell

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.components.KetchMenu
import com.linroid.ketch.app.components.KetchTooltip
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.rememberFocusVisibility
import com.linroid.ketch.app.components.rememberInteractionOverlay
import com.linroid.ketch.app.components.rememberPressScale
import com.linroid.ketch.app.components.trackFocusVisibility
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.input.KetchCommands
import com.linroid.ketch.app.theme.KetchElevationLevel
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.theme.ketchSurface

/** Sizes of an [AddButton]. */
internal object AddButtonDefaults {
  /** The sidebar's title zone. */
  val TitleZone: Dp = 28.dp

  /** The rail. */
  val Rail: Dp = 48.dp
}

/**
 * The round ⊕ that opens the add sheet, on the soft accent: in the sidebar's title zone and at
 * the top of the rail. A right click offers to add the link on the clipboard at once.
 *
 * @param size one of the [AddButtonDefaults] sizes.
 */
@Composable
internal fun AddButton(
  size: Dp,
  onClick: () -> Unit,
  onAddClipboardLink: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val shape = KetchTheme.shapes.full
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val scale = rememberPressScale(interactions)
  val focus = rememberFocusVisibility()
  var menuOpen by remember { mutableStateOf(false) }
  val glyph = if (size >= AddButtonDefaults.Rail) GlyphLarge else KetchTheme.density.controlGlyph
  Box(modifier) {
    KetchTooltip(command = KetchCommands.Add) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .focusRing(focus.visible, shape, colors.focusRing)
          .graphicsLayer {
            scaleX = scale
            scaleY = scale
          }
          .size(size)
          .clip(shape)
          .background(colors.accentSoft)
          .background(overlay)
          // The soft accent is close to the wash it sits on; the outline keeps the button round.
          .border(OutlineWidth, colors.accent.copy(alpha = OUTLINE_ALPHA), shape)
          .onSecondaryPress { menuOpen = true }
          .trackFocusVisibility(focus)
          .semantics { contentDescription = KetchCommands.Add.label }
          .clickable(
            interactionSource = interactions,
            indication = null,
            role = Role.Button,
            onClick = onClick,
          ),
      ) {
        KetchIconImage(KetchIcon.Plus, size = glyph, tint = colors.accentText)
      }
    }
    KetchMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
      item(KetchCommands.AddClipboardLink, onClick = onAddClipboardLink)
    }
  }
}

/**
 * The phone's Add button at the bottom end, which shrinks from "+ Add" to a round 56 dp button
 * once the list scrolls down. A long press adds the link on the clipboard at once.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun AddFab(
  expanded: Boolean,
  onClick: () -> Unit,
  onLongClick: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val motion = KetchTheme.motion
  val shape = KetchTheme.shapes.full
  val interactions = remember { MutableInteractionSource() }
  val overlay = rememberInteractionOverlay(interactions)
  val scale = rememberPressScale(interactions)
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .graphicsLayer {
        scaleX = scale
        scaleY = scale
      }
      .heightIn(min = FabSize)
      .widthIn(min = FabSize)
      .ketchSurface(KetchElevationLevel.E3, shape, colors.accent)
      .background(overlay, shape)
      .combinedClickable(
        interactionSource = interactions,
        indication = null,
        role = Role.Button,
        onClickLabel = KetchCommands.Add.label,
        onLongClickLabel = KetchCommands.AddClipboardLink.label,
        onLongClick = onLongClick,
        onClick = onClick,
      )
      .semantics { contentDescription = "Add" }
      .padding(horizontal = spacing.s4),
  ) {
    KetchIconImage(KetchIcon.Plus, size = GlyphLarge, tint = colors.onAccent)
    AnimatedVisibility(
      visible = expanded,
      enter = expandHorizontally(tween(motion.short)) + fadeIn(tween(motion.short)),
      exit = shrinkHorizontally(tween(motion.short)) + fadeOut(tween(motion.short)),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(Modifier.width(spacing.s3))
        Text(text = "Add", style = KetchTheme.typography.label, color = colors.onAccent)
        Spacer(Modifier.width(spacing.s1))
      }
    }
  }
}

/** Runs [onPress] when the secondary button goes down here, as a right click does. */
internal fun Modifier.onSecondaryPress(onPress: () -> Unit): Modifier = composed {
  val current by rememberUpdatedState(onPress)
  pointerInput(Unit) {
    awaitPointerEventScope {
      while (true) {
        val event = awaitPointerEvent()
        if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed) {
          event.changes.forEach { it.consume() }
          current()
        }
      }
    }
  }
}

private const val OUTLINE_ALPHA = 0.24f

private val OutlineWidth = 1.dp
private val FabSize = 56.dp
private val GlyphLarge = 24.dp
