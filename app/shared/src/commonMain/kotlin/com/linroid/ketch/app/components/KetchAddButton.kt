package com.linroid.ketch.app.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.icons.KetchIconImage
import com.linroid.ketch.app.theme.KetchDensity
import com.linroid.ketch.app.theme.KetchMotion
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.action_add
import ketch.app.shared.generated.resources.component_add_clip
import ketch.app.shared.generated.resources.component_add_drop
import ketch.app.shared.generated.resources.component_add_link
import kotlinx.coroutines.delay
import org.jetbrains.compose.resources.stringResource

/** What a [KetchAddButton] offers. */
@Immutable
internal sealed interface AddButtonMode {
  /** "+ Add", which opens the add sheet. */
  data object Plain : AddButtonMode

  /**
   * A link on the clipboard: "⤓ Add {name}" adds it at once, and the caret beside it opens the
   * add sheet.
   *
   * @property name the link's file name, which the button shortens to the room it has.
   * @property description what adding it does, such as "Download ubuntu.iso from ubuntu.com",
   *   for the tooltip and screen readers.
   * @property id tells clips apart, so the sheen plays once for each.
   */
  data class Clip(val name: String, val description: String, val id: String) : AddButtonMode

  /**
   * A drag from another app hovers the window: "Drop to download".
   *
   * @property over whether the drag is over the button itself.
   */
  data class Drop(val over: Boolean) : AddButtonMode
}

/** The glyph of a [KetchAddButton]. */
internal enum class AddGlyph {
  /** The plus. */
  Plus,

  /** Three download lanes, the stripes of the segmented sail. */
  Lanes,

  /** An arrow down to a line: download this. */
  Arrow,
}

/** The glyph of a button in [mode]: lanes while [hovered] or while a drag waits. */
internal fun addGlyph(mode: AddButtonMode, hovered: Boolean): AddGlyph = when (mode) {
  AddButtonMode.Plain -> if (hovered) AddGlyph.Lanes else AddGlyph.Plus
  is AddButtonMode.Clip -> AddGlyph.Arrow
  is AddButtonMode.Drop -> AddGlyph.Lanes
}

/** Whether the ember gradient fills a button in [mode]: while [hovered], or a drag is over it. */
internal fun addGlows(mode: AddButtonMode, hovered: Boolean): Boolean = when (mode) {
  is AddButtonMode.Drop -> mode.over
  else -> hovered
}

/** Whether the lanes of a button in [mode] keep filling, as while a drag waits for a drop. */
internal fun addLanesFlow(mode: AddButtonMode, reduced: Boolean): Boolean =
  mode is AddButtonMode.Drop && !reduced

/**
 * Whether entering [mode] plays the sheen: once for each new clip, given the [shimmered] clip it
 * last played for, and never while motion is [reduced].
 */
internal fun addShimmers(mode: AddButtonMode, shimmered: String?, reduced: Boolean): Boolean =
  mode is AddButtonMode.Clip && mode.id != shimmered && !reduced

/**
 * The Primary Add button of a page header, 36 dp tall like a [KetchButtonSize.Large] button.
 *
 * Hovering splits its + into three lanes that slide in, the stripes of the segmented sail, and
 * fills it with the ember gradient. A [AddButtonMode.Clip] widens it into a split button, which
 * a sheen crosses once: its main part adds the copied link, its caret opens the add sheet. A
 * [AddButtonMode.Drop] turns it into a dashed drop target whose lanes keep filling, lit by the
 * gradient while the drag is over it. Under reduce motion every change is immediate and the lanes
 * stand still.
 *
 * @param onAdd opens the add sheet; a click while a drag waits does the same.
 * @param onAddClip adds the copied link at once.
 * @param onOpenSheet the caret beside a copied link: opens the add sheet.
 * @param addTooltip what [onAdd] does, for its tooltip and the caret's.
 * @param shortcut chord of [onAdd], shown in those tooltips.
 * @param clipShortcut chord that adds the copied link, shown in the main part's tooltip when its
 *   description is short enough to leave room for it.
 */
@Composable
internal fun KetchAddButton(
  mode: AddButtonMode,
  onAdd: () -> Unit,
  onAddClip: () -> Unit,
  onOpenSheet: () -> Unit,
  modifier: Modifier = Modifier,
  addTooltip: String = stringResource(Res.string.action_add),
  shortcut: String? = null,
  clipShortcut: String? = null,
) {
  val colors = KetchTheme.colors
  val motion = KetchTheme.motion
  val density = KetchTheme.density
  val shape = KetchTheme.shapes.button
  val main = remember { MutableInteractionSource() }
  val caret = remember { MutableInteractionSource() }
  val mainHovered by main.collectIsHoveredAsState()
  val caretHovered by caret.collectIsHoveredAsState()
  val hovered = mainHovered || caretHovered
  val clip = mode as? AddButtonMode.Clip
  val drop = mode as? AddButtonMode.Drop
  val glow = animateFloatAsState(
    targetValue = if (addGlows(mode, hovered)) 1f else 0f,
    animationSpec = tween(motion.short, easing = motion.easeStandard),
    label = "addGlow",
  )
  val soft = animateFloatAsState(
    targetValue = if (drop != null && !drop.over) 1f else 0f,
    animationSpec = tween(motion.short, easing = motion.easeStandard),
    label = "addSoft",
  )
  val grow by animateFloatAsState(
    targetValue = if (drop?.over == true) DROP_OVER_SCALE else 1f,
    animationSpec = tween(motion.short, easing = motion.easeStandard),
    label = "addGrow",
  )
  val sheen = rememberSheen(clip, motion)
  val pressScale = rememberPressScale(main)
  val mainFocus = rememberFocusVisibility()
  val caretFocus = rememberFocusVisibility()
  val ink = lerp(colors.onAccent, colors.accentText, soft.value)
  val padding = if (density == KetchDensity.Comfortable) PaddingComfortable else PaddingCompact
  val mainEnd by animateDpAsState(
    targetValue = if (clip != null) padding - CaretGap else padding,
    animationSpec = tween(motion.medium, easing = motion.easeStandard),
    label = "addMainEnd",
  )
  Row(
    verticalAlignment = Alignment.CenterVertically,
    modifier = modifier
      .focusRing(mainFocus.visible || caretFocus.visible, shape, colors.focusRing)
      .graphicsLayer {
        val scale = pressScale * grow
        scaleX = scale
        scaleY = scale
      }
      .height(density.buttonLarge)
      .clip(shape)
      .drawWithCache {
        val ember = colors.brandEmber
        // 135°, as the logo tile: ember behind the glyph, settling quickly into the accent
        // under the label, which keeps the accent's contrast and never crosses a muddy band.
        val gradient = Brush.linearGradient(
          0f to ember.last(),
          EMBER_STOP to ember.last(),
          ACCENT_STOP to colors.accent,
          start = Offset.Zero,
          end = Offset(size.width, size.height),
        )
        val band = size.width * SHEEN_SHARE
        val sheenBrush = Brush.horizontalGradient(
          colors = listOf(
            Color.Transparent,
            colors.onAccent.copy(alpha = SHEEN_ALPHA),
            Color.Transparent,
          ),
          startX = 0f,
          endX = band,
        )
        val width = DropOutlineWidth.toPx()
        val dash = DropOutlineDash.toPx()
        val dashed = Stroke(
          width = width,
          pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash, dash)),
        )
        val inset = Size(size.width - width, size.height - width)
        val outline = shape.createOutline(inset, layoutDirection, this)
        onDrawWithContent {
          drawRect(lerp(colors.accent, colors.accentSoft, soft.value))
          if (glow.value > 0f) drawRect(gradient, alpha = glow.value)
          drawContent()
          val at = sheen.value
          if (at > 0f && at < 1f) {
            translate(left = -band + (size.width + band) * at) {
              drawRect(sheenBrush, size = Size(band, size.height))
            }
          }
          if (soft.value > 0f) {
            translate(width / 2, width / 2) {
              drawOutline(outline, colors.accent, alpha = soft.value, style = dashed)
            }
          }
        }
      },
  ) {
    Part(
      interactions = main,
      focus = mainFocus,
      onClick = if (clip != null) onAddClip else onAdd,
      tooltip = when (mode) {
        AddButtonMode.Plain -> addTooltip
        is AddButtonMode.Clip -> mode.description
        is AddButtonMode.Drop -> null
      },
      shortcut = when {
        clip == null -> shortcut
        // A description that wraps leaves the bubble no room for the chord.
        clip.description.length <= TOOLTIP_LINE -> clipShortcut
        else -> null
      },
      description = clip?.description,
      start = padding,
      end = mainEnd,
      modifier = Modifier.weight(1f, fill = false),
    ) {
      AddGlyphCanvas(
        glyph = addGlyph(mode, mainHovered),
        flow = addLanesFlow(mode, motion.reduced),
        ink = ink,
        size = density.controlGlyph,
      )
      AnimatedContent(
        targetState = LabelKey(addLabel(mode), clip?.name),
        transitionSpec = {
          val enter = fadeIn(tween(motion.short, delayMillis = motion.micro))
          val exit = fadeOut(tween(motion.micro))
          enter togetherWith exit using SizeTransform(clip = true) { _, _ ->
            tween(motion.medium, easing = motion.easeStandard)
          }
        },
        contentAlignment = Alignment.CenterStart,
        label = "addLabel",
        modifier = Modifier.weight(1f, fill = false),
      ) { label ->
        if (label.clipName != null) {
          ClipLabel(label.clipName, ink)
        } else {
          Text(
            text = label.text.resolve(),
            style = KetchTheme.typography.label,
            color = ink,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
          )
        }
      }
    }
    AnimatedVisibility(
      visible = clip != null,
      enter = expandHorizontally(tween(motion.medium, easing = motion.easeStandard)) +
        fadeIn(tween(motion.short, delayMillis = motion.micro)),
      exit = shrinkHorizontally(tween(motion.medium, easing = motion.easeStandard)) +
        fadeOut(tween(motion.micro)),
    ) {
      Row(verticalAlignment = Alignment.CenterVertically) {
        Spacer(
          Modifier
            .width(SplitWidth)
            .height(density.controlGlyph)
            .background(colors.onAccent.copy(alpha = SPLIT_ALPHA)),
        )
        Part(
          interactions = caret,
          focus = caretFocus,
          onClick = onOpenSheet,
          tooltip = addTooltip,
          shortcut = shortcut,
          description = addTooltip,
          start = CaretGap,
          end = padding - CaretGap,
        ) {
          KetchIconImage(KetchIcon.ChevronDown, size = density.controlGlyph, tint = ink)
        }
      }
    }
  }
}

/** What the label shows: [text], or a copied link's [clipName], which fits itself to its room. */
private data class LabelKey(val text: UiText, val clipName: String?)

/** The label of a button in [mode]. */
internal fun addLabel(mode: AddButtonMode): UiText = when (mode) {
  AddButtonMode.Plain -> Res.string.action_add.text()
  is AddButtonMode.Clip -> Res.string.component_add_clip.text(mode.name)
  is AddButtonMode.Drop -> Res.string.component_add_drop.text()
}

/**
 * [name] cut to at most [max] characters for a button: "…" takes the middle's place, so the
 * start and the end, which tells versions and kinds of file apart, both stay. The end starts at
 * a word where one starts close by, and the start leaves out a word it would cut a few letters
 * into, as in "ubuntu-24.04…amd64.iso".
 */
internal fun shortFileName(name: String, max: Int): String {
  if (name.length <= max) return name
  val budget = max - ELLIPSIS.length
  val dot = name.lastIndexOf('.')
  val extension = if (dot > 0 && name.length - dot - 1 in 1..MAX_EXTENSION) dot else name.length
  val tailStart = name.length - budget / 2
  val word = (tailStart until extension).firstOrNull { name[it - 1] in SEPARATORS }
  // Else a word that starts just before, while the start keeps enough of its own.
  val earlier = (tailStart - 1 downTo maxOf(tailStart - TAIL_REACH, 1)).firstOrNull {
    name[it - 1] in SEPARATORS && budget - (name.length - it) >= MIN_HEAD
  }
  val tail = name.substring(word ?: earlier ?: tailStart)
  var head = name.take(budget - tail.length)
  if (name[head.length] !in SEPARATORS) {
    // A word cut a few letters in is left out, as "-d" in "ubuntu-24.04-d…".
    val wordEnd = head.lastIndexOfAny(SEPARATORS)
    if (wordEnd > 0 && head.length - wordEnd <= HEAD_SNAP) head = head.take(wordEnd)
  }
  return head.trimEnd(*SEPARATORS) + ELLIPSIS + tail
}

/**
 * The label of a copied link's button that [fits]: [template] with the file [name], as "Add
 * ubuntu.iso", the name shortened in the middle as far as needed (see [shortFileName]), or
 * [fallback], "Add link", where even a short name has no room.
 *
 * @param template the label with [CLIP_NAME_SLOT] where the name goes, as
 *   `component_add_clip` formatted with it.
 */
internal fun fitClipLabel(
  name: String,
  template: String,
  fallback: String,
  fits: (String) -> Boolean,
): String {
  for (length in CLIP_NAME_MAX downTo CLIP_NAME_MIN) {
    val label = template.replace(CLIP_NAME_SLOT, shortFileName(name, length))
    if (fits(label)) return label
  }
  return fallback
}

/** A copied link's label, as long as the room the button leaves it allows. */
@Composable
private fun ClipLabel(name: String, color: Color) {
  val style = KetchTheme.typography.label
  val measurer = rememberTextMeasurer()
  val template = stringResource(Res.string.component_add_clip, CLIP_NAME_SLOT)
  val fallback = stringResource(Res.string.component_add_link)
  BoxWithConstraints(contentAlignment = Alignment.CenterStart) {
    val room = constraints.maxWidth
    val label = remember(name, room, style, template, fallback) {
      fitClipLabel(name, template, fallback) {
        measurer.measure(it, style, maxLines = 1).size.width <= room
      }
    }
    Text(
      text = label,
      style = style,
      color = color,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
    )
  }
}

/** One clickable part of a [KetchAddButton], with its own hover and press overlay. */
@Composable
private fun Part(
  interactions: MutableInteractionSource,
  focus: FocusVisibility,
  onClick: () -> Unit,
  tooltip: String?,
  shortcut: String?,
  description: String?,
  start: Dp,
  end: Dp,
  modifier: Modifier = Modifier,
  content: @Composable RowScope.() -> Unit,
) {
  val overlay = rememberInteractionOverlay(interactions)
  KetchTooltip(
    text = tooltip.orEmpty(),
    shortcut = shortcut,
    enabled = tooltip != null,
    modifier = modifier.fillMaxHeight(),
  ) {
    Row(
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(KetchTheme.spacing.iconLabelGap),
      modifier = Modifier
        .fillMaxHeight()
        .background(overlay)
        .ketchClickable(interactions, focus, onClick = onClick)
        .semantics { if (description != null) contentDescription = description }
        .padding(start = start, end = end),
      content = content,
    )
  }
}

/**
 * The sheen of a new clip: from 0 to 1 while it crosses the button, 1 at rest. It plays once
 * per clip, after the button has widened for it, and never under reduce motion.
 */
@Composable
private fun rememberSheen(
  clip: AddButtonMode.Clip?,
  motion: KetchMotion,
): State<Float> {
  val sheen = remember { Animatable(1f) }
  var shimmered by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(clip?.id) {
    if (clip == null) return@LaunchedEffect
    val plays = addShimmers(clip, shimmered, motion.reduced)
    shimmered = clip.id
    if (!plays) return@LaunchedEffect
    delay(motion.medium.toLong())
    sheen.snapTo(0f)
    sheen.animateTo(1f, tween(SHEEN_MILLIS, easing = motion.easeStandard))
  }
  return sheen.asState()
}

/**
 * The glyph of a [KetchAddButton], drawn on the icon grid: the + splits into lanes or gives way
 * to the arrow, and with [flow] the lanes keep filling like a download's.
 */
@Composable
private fun AddGlyphCanvas(glyph: AddGlyph, flow: Boolean, ink: Color, size: Dp) {
  val motion = KetchTheme.motion
  val lanes = animateFloatAsState(
    targetValue = if (glyph == AddGlyph.Lanes) 1f else 0f,
    // The lanes slide in on their own curve, one after the other; they leave quickly.
    animationSpec = tween(
      durationMillis = if (glyph == AddGlyph.Lanes) motion.long else motion.short,
      easing = LinearEasing,
    ),
    label = "addLanes",
  )
  val arrow = animateFloatAsState(
    targetValue = if (glyph == AddGlyph.Arrow) 1f else 0f,
    animationSpec = tween(motion.medium, easing = motion.easeStandard),
    label = "addArrow",
  )
  val cycle = if (flow) rememberLoop(LANE_FLOW_MILLIS, "addFlow") else null
  val easing = motion.easeStandard
  Spacer(
    Modifier
      .size(size)
      .drawBehind { drawAddGlyph(lanes.value, arrow.value, cycle?.value, ink, easing) },
  )
}

/**
 * Draws the glyph on the 20-unit icon grid with a 1.7-unit stroke: [lanes] from the + (0) to the
 * three lanes of [KetchIcon.Lanes] (1), and [arrow] from the + (0) to the arrow (1). [flow], from
 * 0 to 1 and over again, fills the lanes behind moving write heads.
 */
private fun DrawScope.drawAddGlyph(
  lanes: Float,
  arrow: Float,
  flow: Float?,
  ink: Color,
  easing: Easing,
) {
  val unit = size.minDimension / GRID
  val stroke = STROKE * unit
  fun line(x1: Float, y1: Float, x2: Float, y2: Float, alpha: Float) {
    drawLine(
      color = ink,
      start = Offset(x1 * unit, y1 * unit),
      end = Offset(x2 * unit, y2 * unit),
      strokeWidth = stroke,
      cap = StrokeCap.Round,
      alpha = alpha,
    )
  }
  fun head(x: Float, y: Float, scale: Float, alpha: Float) {
    if (scale > 0f) drawCircle(ink, HEAD_RADIUS * unit * scale, Offset(x * unit, y * unit), alpha)
  }
  val plus = 1f - arrow
  if (plus > 0f) {
    // The upright shrinks away while the crossbar becomes the middle lane.
    val upright = 1f - easing.transform(stage(lanes, 0f, UPRIGHT_SHARE))
    if (upright > 0f) line(CENTER, CENTER - ARM * upright, CENTER, CENTER + ARM * upright, plus)
    for ((index, lane) in GlyphLanes.withIndex()) {
      val grown = easing.transform(stage(lanes, lane.delay, lane.delay + LANE_SHARE))
      if (grown <= 0f && lane.fromX == null) continue
      val start = between(lane.fromX ?: LANE_START, LANE_START, grown)
      val end = between(lane.fromEnd ?: LANE_START, lane.end, grown)
      val headAt = stage(lanes, lane.delay + LANE_SHARE * HEAD_AFTER, lane.delay + LANE_SHARE)
      if (flow == null) {
        line(start, lane.y, end, lane.y, plus)
        head(lane.head, lane.y, headAt, plus)
      } else {
        // A drop target's lanes fill one after the other behind their heads, then start over.
        val from = index * FLOW_STAGGER
        val filled = easing.transform(stage(flow, from, from + FLOW_FILL))
        val fade = 1f - stage(flow, FLOW_FADE, 1f)
        val tip = between(LANE_START, lane.end, filled)
        line(LANE_START, lane.y, lane.end, lane.y, plus * TRACK_ALPHA)
        if (filled > 0f) line(LANE_START, lane.y, tip, lane.y, plus * fade)
        head(tip + lane.head - lane.end, lane.y, 1f, plus * fade.coerceAtLeast(TRACK_ALPHA))
      }
    }
  }
  if (arrow > 0f) {
    val drop = -ARROW_DROP * (1f - arrow)
    line(CENTER, ARROW_TOP + drop, CENTER, ARROW_TIP + drop, arrow)
    line(CENTER - ARROW_WING, ARROW_TIP - ARROW_WING + drop, CENTER, ARROW_TIP + drop, arrow)
    line(CENTER + ARROW_WING, ARROW_TIP - ARROW_WING + drop, CENTER, ARROW_TIP + drop, arrow)
    line(ARROW_BASE_START, ARROW_BASE, GRID - ARROW_BASE_START, ARROW_BASE, arrow)
  }
}

/**
 * One lane of the glyph, as in [KetchIcon.Lanes]: at [y] from [LANE_START] to [end], its head at
 * [head], growing in after [delay]. The middle lane grows out of the +'s crossbar, from
 * [fromX] to [fromEnd].
 */
private class GlyphLane(
  val y: Float,
  val end: Float,
  val head: Float,
  val delay: Float,
  val fromX: Float? = null,
  val fromEnd: Float? = null,
)

private val GlyphLanes = listOf(
  GlyphLane(y = 4.5f, end = 11f, head = 12.6f, delay = 0.15f),
  GlyphLane(y = 10f, end = 14.5f, head = 16.1f, delay = 0f, fromX = 4f, fromEnd = 16f),
  GlyphLane(y = 15.5f, end = 7.5f, head = 9.1f, delay = 0.3f),
)

/** How far [value] is through the stretch from [from] to [to], from 0 to 1. */
private fun stage(value: Float, from: Float, to: Float): Float =
  ((value - from) / (to - from)).coerceIn(0f, 1f)

private const val GRID = 20f
private const val STROKE = 1.7f
private const val CENTER = 10f
private const val ARM = 6f
private const val HEAD_RADIUS = 1.6f
private const val LANE_START = 3f
private const val UPRIGHT_SHARE = 0.5f
private const val LANE_SHARE = 0.7f
private const val HEAD_AFTER = 0.6f
private const val ARROW_TOP = 3.5f
private const val ARROW_TIP = 12.5f
private const val ARROW_WING = 3.5f
private const val ARROW_BASE = 16.5f
private const val ARROW_BASE_START = 4.5f
private const val ARROW_DROP = 3f

private const val TRACK_ALPHA = 0.35f
private const val FLOW_STAGGER = 0.12f
private const val FLOW_FILL = 0.45f
private const val FLOW_FADE = 0.85f
private const val LANE_FLOW_MILLIS = 1800

private const val EMBER_STOP = 0.18f
private const val ACCENT_STOP = 0.55f
private const val SHEEN_SHARE = 0.45f
private const val SHEEN_ALPHA = 0.32f
private const val SHEEN_MILLIS = 720
private const val SPLIT_ALPHA = 0.32f

/** Longest copied file name the button shows. */
private const val CLIP_NAME_MAX = 24

/** Shortest copied file name worth showing; with less room the button says "Add link". */
private const val CLIP_NAME_MIN = 10

/** Stands for the file name in a formatted clip label until [fitClipLabel] fills it in. */
internal const val CLIP_NAME_SLOT = "⁣"

/** Longest ending after the last dot that counts as a file extension. */
private const val MAX_EXTENSION = 5

/** Most letters of a word cut at the end of the start part that are left out with it. */
private const val HEAD_SNAP = 4

/** How far back the end part may start to begin at a word. */
private const val TAIL_REACH = 4

/** Fewest characters of the start that the end part leaves when it reaches back. */
private const val MIN_HEAD = 5

/** Characters that end a word in a file name. */
private val SEPARATORS = charArrayOf('-', '_', '.', ' ')

private const val ELLIPSIS = "…"

/** Longest tooltip text that leaves room for a chord on one line. */
private const val TOOLTIP_LINE = 44
private const val DROP_OVER_SCALE = 1.04f

private val PaddingCompact = 16.dp
private val PaddingComfortable = 20.dp
private val CaretGap = 8.dp
private val SplitWidth = 1.dp
private val DropOutlineWidth = 1.5.dp
private val DropOutlineDash = 6.dp
