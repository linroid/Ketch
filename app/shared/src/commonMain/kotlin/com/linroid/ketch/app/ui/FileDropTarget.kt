package com.linroid.ketch.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.draganddrop.dragAndDropTarget
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draganddrop.DragAndDropEvent
import androidx.compose.ui.draganddrop.DragAndDropTarget
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.platform.DragExitEffect
import com.linroid.ketch.app.platform.DroppedFile
import com.linroid.ketch.app.platform.droppedLinkList
import com.linroid.ketch.app.platform.rememberFileDropReader
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.drop_kinds
import ketch.app.shared.generated.resources.drop_to_download
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource

/**
 * Which drop targets of a window a drag from another app hovers now: the window's own
 * [FileDropTarget] and the devices inside it, such as the drop berths. The window shows its drop
 * overlay while any of them does, so it stays up as the drag moves from one to the next.
 */
@Stable
internal class DropHoverState {
  private var targets by mutableStateOf(emptySet<Any>())

  /** Whether a drag from another app hovers the window. */
  val active: Boolean get() = targets.isNotEmpty()

  /** Whether the drag hovers [target]. */
  fun isHovered(target: Any): Boolean = target in targets

  /** Notes that the drag hovers [target]. */
  fun enter(target: Any) {
    if (target !in targets) targets = targets + target
  }

  /** Notes that the drag left [target]. */
  fun exit(target: Any) {
    if (target in targets) targets = targets - target
  }

  /** Forgets every target, as when the drag leaves the window. */
  fun clear() {
    targets = emptySet()
  }
}

/**
 * The [DropHoverState] of the window around this composition, which device drop targets report
 * to; `null` outside the app shell.
 */
internal val LocalWindowDrop = staticCompositionLocalOf<DropHoverState?> { null }

/**
 * Accepts files and text dragged in from other applications anywhere over [content], showing a
 * [DropOverlay] while a drag hovers. A [compact] overlay fits small areas such as a dialog body.
 *
 * @param onDrop receives dropped files, such as `.torrent` files and `.txt` lists of links.
 * @param onDropText receives dropped text, such as a link dragged from a browser or selected
 *   text; by default it reaches [onDrop] as a `.txt` list of links.
 * @param hover where to report the drag instead of drawing the overlay here, for a window that
 *   shows its own, such as the app shell's drop berths.
 */
@Composable
internal fun FileDropTarget(
  onDrop: (List<DroppedFile>) -> Unit,
  modifier: Modifier = Modifier,
  compact: Boolean = false,
  onDropText: (String) -> Unit = { onDrop(listOf(droppedLinkList(it))) },
  hover: DropHoverState? = null,
  content: @Composable BoxScope.() -> Unit,
) {
  val reader = rememberFileDropReader()
  val scope = rememberCoroutineScope()
  val currentOnDrop by rememberUpdatedState(onDrop)
  val currentOnDropText by rememberUpdatedState(onDropText)
  val hovering = hover ?: remember { DropHoverState() }
  val target = remember(reader, scope, hovering) {
    object : DragAndDropTarget {
      override fun onEntered(event: DragAndDropEvent) {
        hovering.enter(this)
      }

      override fun onExited(event: DragAndDropEvent) {
        hovering.exit(this)
      }

      override fun onEnded(event: DragAndDropEvent) {
        hovering.clear()
      }

      override fun onDrop(event: DragAndDropEvent): Boolean {
        hovering.clear()
        val files = reader.files(event)
        if (files.isNotEmpty()) {
          currentOnDrop(files)
          return true
        }
        val text = reader.text(event) ?: return false
        scope.launch {
          val dropped = text()
          if (dropped.isNotBlank()) currentOnDropText(dropped)
        }
        return true
      }
    }
  }
  DragExitEffect { hovering.clear() }
  val fade = tween<Float>(KetchTheme.motion.short)
  Box(
    modifier = modifier.dragAndDropTarget(
      shouldStartDragAndDrop = { reader.accepts(it) },
      target = target,
    ),
  ) {
    content()
    AnimatedVisibility(
      visible = hover == null && hovering.active,
      enter = fadeIn(fade),
      exit = fadeOut(fade),
      modifier = Modifier.matchParentSize(),
    ) {
      DropOverlay(compact)
    }
  }
}

/**
 * What a drag over the app shows: the app fades behind the overlay, and one berth in the middle
 * names what can be dropped (links, magnets, `.torrent` files and lists of links), which is taken
 * wherever it lands. A [compact] berth fills small areas such as a dialog body.
 */
@Composable
internal fun DropOverlay(compact: Boolean, modifier: Modifier = Modifier) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val shape = KetchTheme.shapes.lg
  val tile = if (compact) spacing.s8 else spacing.s12
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier
      .fillMaxSize()
      .background(colors.surfaceRaised.copy(alpha = DROP_OVERLAY_ALPHA))
      .padding(if (compact) spacing.s1 else spacing.s4),
  ) {
    Column(
      modifier = Modifier
        .then(if (compact) Modifier.fillMaxSize() else Modifier)
        .clip(shape)
        .background(colors.accentSoft)
        .dashedOutline(colors.accent, shape)
        .padding(
          horizontal = if (compact) spacing.s6 else spacing.s8,
          vertical = if (compact) spacing.s4 else spacing.s10,
        ),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(spacing.s1, Alignment.CenterVertically),
    ) {
      Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
          .padding(bottom = if (compact) spacing.s1 else spacing.s2)
          .size(tile)
          .background(colors.accent, KetchTheme.shapes.full),
      ) {
        KetchIconImage(KetchIcon.Drop, size = tile / 2, tint = colors.onAccent)
      }
      Text(
        text = stringResource(Res.string.drop_to_download),
        style = if (compact) KetchTheme.typography.bodyStrong else KetchTheme.typography.titleM,
        color = colors.textPrimary,
        textAlign = TextAlign.Center,
      )
      if (!compact) {
        Text(
          text = stringResource(Res.string.drop_kinds),
          style = KetchTheme.typography.bodyS,
          color = colors.textSecondary,
          textAlign = TextAlign.Center,
        )
      }
    }
  }
}

/** A dashed [color] line just inside the edge of [shape]. */
internal fun Modifier.dashedOutline(color: Color, shape: Shape): Modifier = drawWithCache {
  val width = OutlineWidth.toPx()
  val dash = OutlineDash.toPx()
  val dashes = PathEffect.dashPathEffect(floatArrayOf(dash, dash))
  val stroke = Stroke(width = width, pathEffect = dashes)
  val inset = Size(size.width - width, size.height - width)
  val outline = shape.createOutline(inset, layoutDirection, this)
  onDrawBehind {
    translate(width / 2, width / 2) { drawOutline(outline, color, style = stroke) }
  }
}

/** The overlay lets the app show through faintly around the berths, so the drop keeps context. */
internal const val DROP_OVERLAY_ALPHA = 0.96f
private val OutlineWidth = 1.5.dp
private val OutlineDash = 6.dp
