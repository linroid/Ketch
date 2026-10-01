package com.linroid.ketch.app.ui.inspector

import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.semantics.stateDescription
import com.linroid.ketch.api.SpeedLimit
import com.linroid.ketch.app.components.DebouncedCommit
import com.linroid.ketch.app.components.KetchSegmented
import com.linroid.ketch.app.components.SpeedLimitPicker
import com.linroid.ketch.app.components.focusRing
import com.linroid.ketch.app.components.winningLimitCaption
import com.linroid.ketch.app.icons.KetchIcon
import com.linroid.ketch.app.state.formatSpeedLimit
import com.linroid.ketch.app.theme.KetchTheme
import com.linroid.ketch.app.ui.pulse.PopoverAlignment
import com.linroid.ketch.app.ui.pulse.PulsePopover
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

/**
 * The log scale of the speed slider, from 64 KB/s to 100 MB/s, with detents at the round speeds
 * a limit is usually set to.
 */
internal object SpeedScale {
  /** The left end. */
  val Min: SpeedLimit = SpeedLimit.kbps(64)

  /** The right end. */
  val Max: SpeedLimit = SpeedLimit.mbps(100)

  /** Speeds the thumb snaps to, slowest first. */
  val Detents: List<SpeedLimit> = listOf(
    SpeedLimit.kbps(256),
    SpeedLimit.kbps(512),
    SpeedLimit.mbps(1),
    SpeedLimit.mbps(2),
    SpeedLimit.mbps(5),
    SpeedLimit.mbps(10),
    SpeedLimit.mbps(20),
    SpeedLimit.mbps(50),
  )

  /** How close to a detent, in track fractions, the thumb snaps to it. */
  const val SNAP: Float = 0.025f

  private val span = ln(Max.bytesPerSecond.toDouble() / Min.bytesPerSecond)

  /** Where [limit] sits on the track: 0 at [Min], 1 at [Max]; no limit sits at the end. */
  fun fractionOf(limit: SpeedLimit): Float {
    if (limit.isUnlimited) return 1f
    val bytes = limit.bytesPerSecond.coerceIn(Min.bytesPerSecond, Max.bytesPerSecond)
    return (ln(bytes.toDouble() / Min.bytesPerSecond) / span).toFloat()
  }

  /**
   * The limit at [fraction] of the track: a detent within [SNAP] of it, else the speed there
   * kept to two significant digits of its unit, such as 1.4 MB/s or 740 KB/s.
   */
  fun limitAt(fraction: Float): SpeedLimit {
    val at = fraction.coerceIn(0f, 1f)
    Detents.firstOrNull { abs(fractionOf(it) - at) <= SNAP }?.let { return it }
    if (at <= 0f) return Min
    if (at >= 1f) return Max
    val bytes = Min.bytesPerSecond * exp(span * at)
    return SpeedLimit.of(roundToTwoDigits(bytes).coerceIn(Min.bytesPerSecond, Max.bytesPerSecond))
  }

  /**
   * The stop after [limit] towards faster ([direction] > 0) or slower: the ends and the
   * detents, for the arrow keys. No limit counts as [Max].
   */
  fun step(limit: SpeedLimit, direction: Int): SpeedLimit {
    val stops = listOf(Min) + Detents + Max
    val bytes = if (limit.isUnlimited) Max.bytesPerSecond else limit.bytesPerSecond
    return if (direction > 0) {
      stops.firstOrNull { it.bytesPerSecond > bytes } ?: Max
    } else {
      stops.lastOrNull { it.bytesPerSecond < bytes } ?: Min
    }
  }

  private fun roundToTwoDigits(bytes: Double): Long {
    val unit = if (bytes >= MIB) MIB else KIB
    val amount = bytes / unit
    val step = 10.0.pow(floor(log10(amount)) - 1)
    return ((amount / step).roundToLong() * step * unit).roundToLong()
  }

  private const val KIB = 1024.0
  private const val MIB = 1024.0 * 1024.0
}

/**
 * An option of the Speed control: no limit, a preset, or the "⋯" that opens the slider, which
 * shows a [custom] limit while one is set.
 */
@Immutable
internal data class SpeedOption(val limit: SpeedLimit?, val custom: SpeedLimit? = null) {
  /** Whether this is the "⋯" option. */
  val isMore: Boolean get() = limit == null
}

/**
 * The Speed control: `Unlimited │ 5 MB/s │ 2 MB/s │ 1 MB/s │ ⋯`, with as many of [presets] as
 * fit. "⋯" opens a 280 dp popover, or a sheet on touch, with a log slider and the
 * [SpeedLimitPicker] field; a limit outside the presets shows in its place. [value] is `null`
 * when the downloads it sets have different limits, which selects nothing.
 *
 * @param globalCap the device's limit, marked on the slider as [globalName].
 * @param onCommit applies a newly chosen limit, once per choice.
 */
@Composable
internal fun SpeedControl(
  value: SpeedLimit?,
  presets: List<SpeedLimit>,
  onCommit: (SpeedLimit) -> Unit,
  globalCap: SpeedLimit,
  globalName: String,
  pending: Boolean,
  modifier: Modifier = Modifier,
) {
  val (shown, choose) = rememberChoice(value, pending)
  var popover by remember { mutableStateOf(false) }
  val commit: (SpeedLimit) -> Unit = { limit ->
    if (limit != shown) {
      choose(limit)
      onCommit(limit)
    }
  }
  Box(modifier) {
    FirstThatFits(count = presets.size + 1) { variant ->
      val kept = presets.take(presets.size - variant)
      val custom = shown?.takeIf { !it.isUnlimited && it !in kept }
      val options = listOf(SpeedOption(SpeedLimit.Unlimited)) +
        kept.map { SpeedOption(it) } + SpeedOption(null, custom)
      val selected = when {
        shown == null -> null
        custom != null -> options.last()
        else -> options.firstOrNull { it.limit == shown }
      }
      KetchSegmented(
        options = options,
        selected = selected,
        onSelect = { option ->
          val limit = option?.limit
          if (limit == null) popover = true else commit(limit)
        },
        label = { option -> option?.label().orEmpty() },
        icon = { option ->
          KetchIcon.More.takeIf { option?.isMore == true && option.custom == null }
        },
      )
    }
    PulsePopover(
      expanded = popover,
      onDismissRequest = { popover = false },
      width = KetchTheme.spacing.s16 * 4 + KetchTheme.spacing.s6,
      alignment = PopoverAlignment.End,
    ) {
      SpeedPopoverContent(
        value = shown ?: SpeedLimit.Unlimited,
        onCommit = commit,
        globalCap = globalCap,
        globalName = globalName,
        pending = pending,
      )
    }
  }
}

private fun SpeedOption.label(): String = when {
  custom != null -> formatSpeedLimit(custom)
  limit == null -> ""
  else -> formatSpeedLimit(limit)
}

/** The slider over the [SpeedLimitPicker], with the limit that wins named under it. */
@Composable
private fun SpeedPopoverContent(
  value: SpeedLimit,
  onCommit: (SpeedLimit) -> Unit,
  globalCap: SpeedLimit,
  globalName: String,
  pending: Boolean,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val type = KetchTheme.typography
  var preview by remember { mutableStateOf<SpeedLimit?>(null) }
  Column(verticalArrangement = Arrangement.spacedBy(spacing.s3)) {
    Row(verticalAlignment = Alignment.CenterVertically) {
      Text(
        text = "Speed limit",
        style = type.label,
        color = colors.textSecondary,
        modifier = Modifier.weight(1f),
      )
      Text(
        text = formatSpeedLimit(preview ?: value),
        style = type.numeral,
        color = colors.textPrimary,
      )
    }
    Column(verticalArrangement = Arrangement.spacedBy(spacing.s1)) {
      LogSpeedSlider(
        value = value,
        onCommit = onCommit,
        onPreview = { preview = it },
        marker = globalCap,
      )
      Row {
        Text(
          text = formatSpeedLimit(SpeedScale.Min),
          style = type.numeralS,
          color = colors.textTertiary,
          modifier = Modifier.weight(1f),
        )
        Text(
          text = formatSpeedLimit(SpeedScale.Max),
          style = type.numeralS,
          color = colors.textTertiary,
        )
      }
      if (!globalCap.isUnlimited) {
        Row(
          verticalAlignment = Alignment.CenterVertically,
          horizontalArrangement = Arrangement.spacedBy(spacing.s1),
        ) {
          Spacer(
            Modifier
              .size(width = spacing.s0_5, height = spacing.s3)
              .drawWithCache {
                onDrawBehind { drawRect(colors.status.paused.color) }
              },
          )
          Text(
            text = "$globalName ${formatSpeedLimit(globalCap)}",
            style = type.caption,
            color = colors.textSecondary,
          )
        }
      }
    }
    SpeedLimitPicker(
      value = value,
      onCommit = onCommit,
      caption = winningLimitCaption(value, globalCap, globalName),
      pending = pending,
    )
  }
}

/**
 * A slider over [SpeedScale] that commits once, when the thumb is let go. While it is dragged
 * [onPreview] receives the limit under it, then `null`. The arrow keys move between the
 * detents and commit after a short pause, so a run of presses sends one limit. A [marker] that
 * limits is drawn as a tick on the track.
 */
@Composable
internal fun LogSpeedSlider(
  value: SpeedLimit,
  onCommit: (SpeedLimit) -> Unit,
  modifier: Modifier = Modifier,
  onPreview: (SpeedLimit?) -> Unit = {},
  marker: SpeedLimit = SpeedLimit.Unlimited,
) {
  val colors = KetchTheme.colors
  val spacing = KetchTheme.spacing
  val interactions = remember { MutableInteractionSource() }
  val focused by interactions.collectIsFocusedAsState()
  var dragging by remember { mutableStateOf<Float?>(null) }
  var stepped by remember { mutableStateOf<SpeedLimit?>(null) }
  val currentOnCommit by rememberUpdatedState(onCommit)
  val currentOnPreview by rememberUpdatedState(onPreview)
  val scope = rememberCoroutineScope()
  val keys = remember(scope) {
    DebouncedCommit<SpeedLimit>(scope, KEY_COMMIT_DELAY) { limit ->
      stepped = null
      currentOnPreview(null)
      currentOnCommit(limit)
    }
  }
  DisposableEffect(keys) { onDispose { keys.flush() } }
  val shown = dragging?.let(SpeedScale::limitAt) ?: stepped ?: value
  val fraction = SpeedScale.fractionOf(shown)
  val thumbRadius = spacing.s2
  fun commitKey(limit: SpeedLimit) {
    stepped = limit
    currentOnPreview(limit)
    keys.update(limit)
  }
  Box(
    modifier = modifier
      .fillMaxWidth()
      .height(KetchTheme.density.buttonSmall)
      .semantics {
        contentDescription = "Speed limit"
        stateDescription = formatSpeedLimit(shown)
        progressBarRangeInfo = ProgressBarRangeInfo(fraction, 0f..1f)
        setProgress { target ->
          currentOnCommit(SpeedScale.limitAt(target))
          true
        }
      }
      .onKeyEvent { event ->
        if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
        when (event.key) {
          Key.DirectionRight, Key.DirectionUp -> commitKey(SpeedScale.step(shown, 1))
          Key.DirectionLeft, Key.DirectionDown -> commitKey(SpeedScale.step(shown, -1))
          Key.MoveHome -> commitKey(SpeedScale.Min)
          Key.MoveEnd -> commitKey(SpeedScale.Max)
          else -> return@onKeyEvent false
        }
        true
      }
      .focusable(interactionSource = interactions)
      .pointerInput(Unit) {
        val inset = thumbRadius.toPx()
        fun fractionAt(x: Float): Float =
          ((x - inset) / (size.width - inset * 2).coerceAtLeast(1f)).coerceIn(0f, 1f)
        awaitEachGesture {
          val down = awaitFirstDown()
          down.consume()
          dragging = fractionAt(down.position.x)
          currentOnPreview(SpeedScale.limitAt(dragging ?: 0f))
          while (true) {
            val event = awaitPointerEvent()
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            change.consume()
            if (!change.pressed) break
            dragging = fractionAt(change.position.x)
            currentOnPreview(SpeedScale.limitAt(dragging ?: 0f))
          }
          val released = dragging
          dragging = null
          currentOnPreview(null)
          if (released != null) currentOnCommit(SpeedScale.limitAt(released))
        }
      }
      .drawWithCache {
        val radius = thumbRadius.toPx()
        val track = spacing.s1.toPx()
        val dot = spacing.s0_5.toPx() * DOT_SHARE
        val left = radius
        val width = size.width - radius * 2
        val mid = size.height / 2
        val corner = CornerRadius(track / 2)
        val markerFraction = if (marker.isUnlimited) null else SpeedScale.fractionOf(marker)
        val markerHeight = spacing.s3.toPx()
        val markerWidth = spacing.s0_5.toPx()
        val ring = Stroke(spacing.s0_5.toPx())
        val hairline = Stroke(spacing.s0_5.toPx() / 2)
        onDrawBehind {
          val thumbX = left + width * fraction
          drawRoundRect(
            color = colors.surfaceSunken,
            topLeft = Offset(left, mid - track / 2),
            size = Size(width, track),
            cornerRadius = corner,
          )
          drawRoundRect(
            color = colors.accent,
            topLeft = Offset(left, mid - track / 2),
            size = Size(thumbX - left, track),
            cornerRadius = corner,
          )
          for (detent in SpeedScale.Detents) {
            val x = left + width * SpeedScale.fractionOf(detent)
            val tint = if (x <= thumbX) colors.onAccent else colors.textTertiary
            drawCircle(tint, dot, Offset(x, mid))
          }
          if (markerFraction != null) {
            drawRect(
              color = colors.status.paused.color,
              topLeft = Offset(
                left + width * markerFraction - markerWidth / 2,
                mid - markerHeight / 2,
              ),
              size = Size(markerWidth, markerHeight),
            )
          }
          val thumb = Offset(thumbX, mid)
          if (focused) drawCircle(colors.focusRing, radius + ring.width * 2, thumb, style = ring)
          drawCircle(colors.surface, radius, thumb)
          drawCircle(colors.borderStrong, radius, thumb, style = hairline)
        }
      },
  )
}

private const val DOT_SHARE = 0.75f
private val KEY_COMMIT_DELAY = 400.milliseconds
