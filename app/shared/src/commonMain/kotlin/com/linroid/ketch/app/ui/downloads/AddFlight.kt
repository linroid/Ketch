package com.linroid.ketch.app.ui.downloads

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationVector1D
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.Spacer
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.unit.dp
import com.linroid.ketch.app.state.AppState
import com.linroid.ketch.app.state.TaskKey
import com.linroid.ketch.app.theme.KetchMotion
import com.linroid.ketch.app.theme.KetchTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/** What the row of a just added download does when it comes on screen. */
internal enum class AddedAction {
  /** A lane flies to it from the Add button, and it glows as the lane lands. */
  Fly,

  /** It glows at once. */
  Glow,

  /** It glows when the lane in the air lands, with that lane's row. */
  Hold,

  /** Nothing: it is already being pointed out. */
  None,
}

/**
 * Where each download just added from this window is in being pointed out, by task. A download
 * starts fresh; the first fresh row to show gets the lane while one may fly, the others glow
 * with it as it lands. Once [settle]d, rows that never showed only glow when they do, until they
 * are [forget]ten. Kept in snapshot state, so rows can tell whether they are tracked.
 */
internal class AddedRows {
  private enum class Phase { Fresh, Waiting, Held, Flying, Glowing }

  private val phases = mutableStateMapOf<TaskKey, Phase>()

  /** Whether the row of [key] is being pointed out, so it reports where it is. */
  fun tracks(key: TaskKey): Boolean = key in phases

  /** Tracks [keys], just added. */
  fun add(keys: List<TaskKey>) {
    for (key in keys) phases[key] = Phase.Fresh
  }

  /** What the row of [key] does now that it shows; a lane flies only when [canFly]. */
  fun shown(key: TaskKey, canFly: Boolean): AddedAction = when (phases[key]) {
    Phase.Fresh -> when {
      Phase.Flying in phases.values -> set(key, Phase.Held, AddedAction.Hold)
      canFly -> set(key, Phase.Flying, AddedAction.Fly)
      else -> set(key, Phase.Glowing, AddedAction.Glow)
    }
    Phase.Waiting -> set(key, Phase.Glowing, AddedAction.Glow)
    else -> AddedAction.None
  }

  /** The lane landed on the row of [key]: returns it and the rows held for it, which now glow. */
  fun landed(key: TaskKey): List<TaskKey> {
    if (phases[key] != Phase.Flying) return emptyList()
    val held = phases.filterValues { it == Phase.Held }.keys
    val glowing = listOf(key) + held
    for (glowingKey in glowing) phases[glowingKey] = Phase.Glowing
    return glowing
  }

  /** The rows of [keys] that did not show in time get no lane; they glow once they show. */
  fun settle(keys: List<TaskKey>) {
    for (key in keys) if (phases[key] == Phase.Fresh) phases[key] = Phase.Waiting
  }

  /** Stops waiting for the rows of [keys] that never showed. */
  fun forget(keys: List<TaskKey>) {
    for (key in keys) if (phases[key] == Phase.Waiting) phases.remove(key)
  }

  /** The row of [key] finished glowing. */
  fun glowed(key: TaskKey) {
    if (phases[key] == Phase.Glowing) phases.remove(key)
  }

  private fun set(key: TaskKey, phase: Phase, action: AddedAction): AddedAction {
    phases[key] = phase
    return action
  }
}

/**
 * Points out the rows of downloads just added from this window ([AppState.addedTasks]): a lane
 * flies from the header's Add button to the first new row on screen and the new rows glow
 * (`rowSelected` fading out over 1.2 s) as it lands. A row that is not on screen, such as on
 * another tab or scrolled away, gets no lane and glows when it shows, if that is soon. Under
 * reduce motion, or without the Add button, as on phones, rows only glow.
 *
 * Rows report where they are only while they are pointed out ([addedRow]); the button reports
 * through [addFlightOrigin], and one [AddFlightOverlay] over the page draws the lane.
 */
@Stable
internal class AddFlight(private val scope: CoroutineScope) {
  private val rows = AddedRows()
  private val positions = HashMap<TaskKey, LayoutCoordinates>()
  private val glows = mutableStateMapOf<TaskKey, Animatable<Float, AnimationVector1D>>()
  private val lane = Animatable(0f)
  private var flying by mutableStateOf<TaskKey?>(null)

  /** The Add button, where lanes start, while it is shown. */
  var origin: LayoutCoordinates? = null

  /** The overlay the lane is drawn in. */
  var overlay: LayoutCoordinates? = null

  /** The page's motion tokens: whether a lane may fly, and its easing. */
  var motion: KetchMotion = KetchMotion()

  /** Whether the row of [key] is pointed out now, or will be once it shows. */
  fun tracks(key: TaskKey): Boolean = rows.tracks(key)

  /** How strongly the row of [key] glows, from 0 to 1; read while drawing. */
  fun glowOf(key: TaskKey): Float = glows[key]?.value ?: 0f

  /** Starts pointing out the rows of [keys], just added. */
  fun onAdded(keys: List<TaskKey>) {
    rows.add(keys)
    scope.launch {
      delay(ROW_WAIT)
      rows.settle(keys)
      delay(ROW_LINGER)
      rows.forget(keys)
    }
  }

  /** The row of [key] was placed at [coordinates]. */
  fun onRowPositioned(key: TaskKey, coordinates: LayoutCoordinates) {
    positions[key] = coordinates
    if (!coordinates.isOnScreen()) return
    when (rows.shown(key, canFly())) {
      AddedAction.Fly -> fly(key)
      AddedAction.Glow -> glow(key)
      AddedAction.Hold, AddedAction.None -> Unit
    }
  }

  /** The row of [key] left the screen. */
  fun onRowGone(key: TaskKey) {
    positions.remove(key)
  }

  /** Draws the lane in the air, if there is one, in the [overlay]'s coordinates. */
  fun drawLane(draw: DrawScope, color: Color) {
    val key = flying ?: return
    val time = lane.value
    val overlay = overlay?.takeIf { it.isAttached } ?: return
    val button = origin?.takeIf { it.isAttached } ?: return
    val row = positions[key]?.takeIf { it.isAttached } ?: return
    val from = overlay.localBoundingBoxOf(button, clipBounds = false)
    val to = overlay.localBoundingBoxOf(row, clipBounds = false)
    with(draw) {
      val start = Offset(from.left + OriginInset.toPx(), from.center.y)
      val end = Offset(to.left + TargetInset.toPx(), to.center.y)
      // The lane turns into the row's line by the row's end, clear of a docked inspector.
      val turn = Offset(minOf(start.x, to.right - TargetInset.toPx()), end.y)
      drawAddLane(start, turn, end, time, color, motion.easeStandard)
    }
  }

  private fun canFly(): Boolean =
    !motion.reduced && origin?.isAttached == true && overlay?.isAttached == true

  private fun fly(key: TaskKey) {
    flying = key
    scope.launch {
      var landed = false
      lane.snapTo(0f)
      lane.animateTo(1f, tween(motion.xlong, easing = LinearEasing)) {
        // The row starts to glow as the lane slows down on it, then the lane fades into it.
        if (!landed && value >= LAND_AT) {
          landed = true
          rows.landed(key).forEach { glow(it) }
        }
      }
      if (!landed) rows.landed(key).forEach { glow(it) }
      flying = null
    }
  }

  private fun glow(key: TaskKey) {
    val fade = Animatable(1f)
    glows[key] = fade
    scope.launch {
      fade.animateTo(0f, tween(GLOW_FADE_MILLIS, delayMillis = GLOW_HOLD_MILLIS))
      if (glows[key] === fade) glows.remove(key)
      rows.glowed(key)
    }
  }
}

/** The page's [AddFlight], which rows and the Add button report to; `null` outside it. */
internal val LocalAddFlight = staticCompositionLocalOf<AddFlight?> { null }

/** The [AddFlight] of the Downloads page, fed with the downloads [state] adds. */
@Composable
internal fun rememberAddFlight(state: AppState): AddFlight {
  val scope = rememberCoroutineScope()
  val motion = KetchTheme.motion
  val flight = remember(state, scope) { AddFlight(scope) }
  SideEffect { flight.motion = motion }
  LaunchedEffect(state, flight) { state.addedTasks.collect(flight::onAdded) }
  return flight
}

/** Draws the lane of [flight] over the page; it takes no input. */
@Composable
internal fun AddFlightOverlay(flight: AddFlight, modifier: Modifier = Modifier) {
  val accent = KetchTheme.colors.accent
  Spacer(
    modifier
      .onGloballyPositioned { flight.overlay = it }
      .drawBehind { flight.drawLane(this, accent) },
  )
}

/** Makes this the Add button that lanes of new downloads fly from. */
@Composable
internal fun Modifier.addFlightOrigin(): Modifier {
  val flight = LocalAddFlight.current ?: return this
  DisposableEffect(flight) { onDispose { flight.origin = null } }
  return onGloballyPositioned { flight.origin = it }
}

/**
 * Lets the page point out the row of [key] while its download was just added: the lane lands on
 * it and it glows in [shape]. Other rows are left alone and report nothing.
 */
@Composable
internal fun Modifier.addedRow(key: TaskKey, shape: Shape = RectangleShape): Modifier {
  val flight = LocalAddFlight.current ?: return this
  val tracked by remember(flight, key) { derivedStateOf { flight.tracks(key) } }
  if (!tracked) return this
  val color = KetchTheme.colors.rowSelected
  DisposableEffect(flight, key) { onDispose { flight.onRowGone(key) } }
  return this
    .onGloballyPositioned { flight.onRowPositioned(key, it) }
    .drawWithCache {
      val outline = shape.createOutline(size, layoutDirection, this)
      onDrawBehind {
        val glow = flight.glowOf(key)
        if (glow > 0f) drawOutline(outline, color, alpha = glow)
      }
    }
}

/**
 * Draws a lane on its way from [start] to [end] at [time], from 0 to 1: it drops from the button
 * and curves toward [turn] into the row's line, a streak that is long while it is fast and short
 * as it slows down, with a bright head. It fades in as it leaves and out as it lands.
 */
internal fun DrawScope.drawAddLane(
  start: Offset,
  turn: Offset,
  end: Offset,
  time: Float,
  color: Color,
  easing: Easing,
) {
  val head = easing.transform(time)
  val tail = easing.transform((time - TRAIL).coerceAtLeast(0f))
  val alpha = fraction(time, 0f, FADE_IN) * (1f - fraction(time, LAND_AT, 1f))
  if (alpha <= 0f) return
  val path = Path()
  var last = start
  for (step in 0..TRAIL_STEPS) {
    val at = tail + (head - tail) * step / TRAIL_STEPS
    last = bezier(start, turn, end, at)
    if (step == 0) path.moveTo(last.x, last.y) else path.lineTo(last.x, last.y)
  }
  val first = bezier(start, turn, end, tail)
  val width = LaneWidth.toPx()
  val brush = Brush.linearGradient(
    colors = listOf(color.copy(alpha = 0f), color),
    start = first,
    end = last,
  )
  drawPath(
    path = path,
    brush = brush,
    alpha = alpha,
    style = Stroke(width = width, cap = StrokeCap.Round, join = StrokeJoin.Round),
  )
  drawCircle(color, radius = HaloRadius.toPx(), center = last, alpha = alpha * HALO_ALPHA)
  drawCircle(color, radius = width, center = last, alpha = alpha)
}

/** Whether at least half of this row shows, inside the list and the window. */
private fun LayoutCoordinates.isOnScreen(): Boolean {
  if (!isAttached || size.height == 0) return false
  val shown = boundsInWindow()
  return shown.width > 0f && shown.height >= size.height * ON_SCREEN_SHARE
}

private fun bezier(start: Offset, control: Offset, end: Offset, t: Float): Offset {
  val rest = 1f - t
  return start * (rest * rest) + control * (2 * rest * t) + end * (t * t)
}

private fun fraction(value: Float, from: Float, to: Float): Float =
  ((value - from) / (to - from)).coerceIn(0f, 1f)

/** How long a new row has to show to get the lane. */
private val ROW_WAIT: Duration = 1.seconds

/** How long a new row that is not on screen still glows once it shows. */
private val ROW_LINGER: Duration = 20.seconds

private const val GLOW_HOLD_MILLIS = 300
private const val GLOW_FADE_MILLIS = 900
private const val ON_SCREEN_SHARE = 0.5f
private const val LAND_AT = 0.82f
private const val FADE_IN = 0.08f
private const val TRAIL = 0.14f
private const val TRAIL_STEPS = 10
private const val HALO_ALPHA = 0.22f

/** From the Add button's start to the middle of its glyph. */
private val OriginInset = 24.dp

/** From a row's start to where the lane lands, by its status dot or file chip. */
private val TargetInset = 24.dp

private val LaneWidth = 3.dp
private val HaloRadius = 8.dp
