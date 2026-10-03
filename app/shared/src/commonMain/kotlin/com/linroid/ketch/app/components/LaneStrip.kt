package com.linroid.ketch.app.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.linroid.ketch.api.DownloadProgress
import com.linroid.ketch.api.DownloadState
import com.linroid.ketch.api.Segment
import com.linroid.ketch.app.i18n.UiText
import com.linroid.ketch.app.i18n.joinText
import com.linroid.ketch.app.i18n.resolve
import com.linroid.ketch.app.i18n.text
import com.linroid.ketch.app.theme.KetchColors
import com.linroid.ketch.app.theme.KetchTheme
import ketch.app.shared.generated.resources.Res
import ketch.app.shared.generated.resources.component_lanes_active
import ketch.app.shared.generated.resources.component_lanes_connections
import ketch.app.shared.generated.resources.component_lanes_percent
import ketch.app.shared.generated.resources.component_lanes_size_unknown
import ketch.app.shared.generated.resources.component_lanes_stalled
import kotlinx.coroutines.launch

/** Heights of a [LaneStrip] in each place it appears. */
object LaneStripDefaults {
  /** Under the second line of a list row. */
  val RowHeight: Dp = 4.dp

  /** In the table's Progress column and the Devices page's device lane. */
  val CellHeight: Dp = 6.dp

  /** One connection's own lane in the Connections tab. */
  val LaneHeight: Dp = 8.dp

  /** Under the name in the inspector header. */
  val HeaderHeight: Dp = 10.dp

  /** The file map of the Connections tab. */
  val MapHeight: Dp = 16.dp

  /** Strips with more segments than this draw no seams, which would blur into a grey bar. */
  const val MAX_SEAMED_SEGMENTS: Int = 32
}

/**
 * Ketch's signature view of a download: every connection as a lane at its real place in the
 * file, drawn in one canvas pass.
 *
 * Each segment spans `start / total` to `(end + 1) / total` of the width with its downloaded
 * part filled in the accent lane ramp; finished neighbours share one color, so finished runs read
 * as one. 1 dp seams separate unfinished segments while there are at most
 * [LaneStripDefaults.MAX_SEAMED_SEGMENTS]. While downloading, every unfinished segment carries a
 * write head that glides to each new offset over the engine's 200 ms progress cadence; a head in
 * [stalled] turns amber and loses its glow. Heads reach 2 dp above and below the strip.
 *
 * When the connections change, new seams spring out from the old write heads and flash accent.
 * A download of unknown size shows a shimmer instead of lanes. On completion the lanes fade into
 * one run in the completed color, an ember sheen crosses it once and [onCompletionShown] runs, so
 * the caller can remove the strip. Paused, waiting and failed tasks show their map without heads.
 * Under reduce motion nothing glides, springs, flashes or shimmers, and the sheen is skipped.
 *
 * The strip animates from one snapshot of a file to the next; a snapshot of a file of another
 * size starts over without motion. A place that shows different tasks in turn, such as the
 * inspector, keys the strip by task so a completion or re-split never carries over.
 *
 * @param state state of the task, which picks the colors and gives the size when there are no
 *   segments.
 * @param segments the task's segments, in any order.
 * @param height one of the [LaneStripDefaults] heights.
 * @param highlight start byte of a segment to highlight, dimming the others, as when its lane is
 *   hovered in the Connections tab.
 * @param stalled start bytes of segments that have received no data for a while.
 * @param onCompletionShown runs once the completion sheen has played, or as soon as the strip is
 *   shown for a task that is already complete.
 */
@Composable
fun LaneStrip(
  state: DownloadState,
  segments: List<Segment>,
  modifier: Modifier = Modifier,
  height: Dp = LaneStripDefaults.RowHeight,
  highlight: Long? = null,
  stalled: Set<Long> = emptySet(),
  onCompletionShown: () -> Unit = {},
) {
  val phase = state.lanePhase()
  LaneStripCanvas(
    segments = segments,
    phase = phase,
    progress = state.laneProgress(),
    modifier = modifier,
    height = height,
    highlight = highlight,
    stalled = stalled,
    onCompletionShown = onCompletionShown,
  )
}

/** What a lane strip shows, from the state of its task. */
internal enum class LanePhase {
  /** The accent lanes, with write heads. */
  Downloading,

  /** The lanes in the paused color. */
  Paused,

  /** Queued or scheduled with progress kept: the lanes in the queued color. */
  Waiting,

  /** Failed or canceled: the map in faint tertiary ink. */
  Failed,

  /** One merged run in the completed color. */
  Completed,
}

internal fun DownloadState.lanePhase(): LanePhase = when (this) {
  is DownloadState.Downloading -> LanePhase.Downloading
  is DownloadState.Paused -> LanePhase.Paused
  is DownloadState.Queued, is DownloadState.Scheduled -> LanePhase.Waiting
  is DownloadState.Failed, is DownloadState.Canceled -> LanePhase.Failed
  is DownloadState.Completed -> LanePhase.Completed
}

private fun DownloadState.laneProgress(): DownloadProgress? = when (this) {
  is DownloadState.Downloading -> progress
  is DownloadState.Paused -> progress
  is DownloadState.Completed -> totalBytes?.let { DownloadProgress(it, it) }
  else -> null
}

/**
 * The strip behind [LaneStrip], also drawn by the inspector's Connections and Files tabs. A
 * single connection's own lane is one segment moved to start at byte 0, with no [progress].
 *
 * @param heads whether unfinished segments carry write heads.
 */
@Composable
internal fun LaneStripCanvas(
  segments: List<Segment>,
  phase: LanePhase,
  progress: DownloadProgress?,
  modifier: Modifier = Modifier,
  height: Dp = LaneStripDefaults.RowHeight,
  heads: Boolean = phase == LanePhase.Downloading,
  highlight: Long? = null,
  stalled: Set<Long> = emptySet(),
  onCompletionShown: () -> Unit = {},
) {
  val colors = KetchTheme.colors
  val motion = KetchTheme.motion
  val corner = KetchTheme.shapes.xs
  val palettes = remember(colors) { LanePalettes(colors) }
  val strip = remember {
    LaneStripState().apply { update(segments, progress, stalled, glideAt = 1f, resegAt = 1f) }
  }
  val scope = rememberCoroutineScope()
  val completionShown by rememberUpdatedState(onCompletionShown)

  LaunchedEffect(segments, progress, stalled) {
    val changed = strip.update(segments, progress, stalled, strip.glide.value, strip.reseg.value)
    if (!changed) return@LaunchedEffect
    if (strip.layout.resegmented && !motion.reduced) {
      // Snapped here, so no frame draws the new seams at rest before they spring.
      strip.reseg.snapTo(0f)
      strip.flash.snapTo(1f)
      scope.launch { strip.reseg.animateTo(1f, motion.progressSpring) }
      scope.launch { strip.flash.animateTo(0f, tween(SEAM_FLASH_MILLIS, easing = LinearEasing)) }
    }
    strip.glide.snapTo(0f)
    strip.glide.animateTo(1f, motion.headGlide)
  }
  LaunchedEffect(phase) {
    val previous = strip.phase
    strip.phase = phase
    if (phase != LanePhase.Completed) return@LaunchedEffect
    if (previous != null && previous != LanePhase.Completed && !motion.reduced) {
      strip.completion.snapTo(0f)
      strip.completion.animateTo(
        targetValue = 1f,
        animationSpec = tween(motion.medium + motion.long, easing = LinearEasing),
      )
    } else {
      strip.completion.snapTo(1f)
    }
    completionShown()
  }

  val indeterminate = segments.isEmpty() && (progress == null || progress.totalBytes <= 0)
  val shimmering = indeterminate && phase == LanePhase.Downloading && !motion.reduced
  val shimmer = if (shimmering) rememberLoop(SHIMMER_MILLIS, "laneShimmer") else null
  val description = remember(segments, progress, phase, stalled) {
    laneStripDescription(segments, progress, phase, stalled.size)
  }.resolve()
  val fraction = remember(segments, progress, phase) { laneFraction(segments, progress, phase) }
  val palette = palettes.of(phase)
  val fadeMillis = motion.medium
  val sheenMillis = motion.long

  Canvas(
    modifier
      .fillMaxWidth()
      .height(height)
      .semantics {
        contentDescription = description
        progressBarRangeInfo = if (fraction != null) {
          ProgressBarRangeInfo(fraction, 0f..1f)
        } else {
          ProgressBarRangeInfo.Indeterminate
        }
      }
  ) {
    val frame = strip.frame
    frame.layout = strip.layout
    frame.glide = strip.glide.value
    frame.reseg = strip.reseg.value
    frame.highlighted = highlight != null
    frame.highlight = highlight ?: 0L
    val count = frame.layout.count
    val radius = corner.topStart.toPx(size, this).coerceAtMost(size.height / 2)
    drawRoundRect(colors.surfaceSunken, cornerRadius = CornerRadius(radius))
    val done = phase == LanePhase.Completed
    // Until the phase effect has seen the completion, keep drawing the lanes it fades from.
    val completing = done && strip.phase != null && strip.phase != LanePhase.Completed
    val completion = if (completing) 0f else strip.completion.value
    val fade = if (done) completionFade(completion, fadeMillis, sheenMillis) else 0f
    clipPath(strip.clipPath(size, radius)) {
      if (count == 0 && !done) {
        if (phase == LanePhase.Downloading && frame.layout.fraction == null) {
          drawIndeterminate(palette.first(), shimmer?.value)
        }
        return@clipPath
      }
      if (fade < 1f) {
        drawFills(frame, palette, alpha = 1f - fade)
        drawSeams(frame, colors.surface, colors.accent, strip.flash.value, alpha = 1f - fade)
      }
      if (done) {
        drawRect(colors.status.completed.color, alpha = COMPLETED_ALPHA * fade)
        val sheen = completionSheen(completion, fadeMillis, sheenMillis)
        if (sheen > 0f && sheen < 1f) drawSheen(strip, colors.brandEmber, sheen)
      }
    }
    if (heads && !done) drawHeads(frame, colors.accent, colors.status.paused.color)
  }
}

/**
 * The layout and where the strip's animations are at the frame being drawn. One instance per
 * strip is refilled for every frame, so drawing allocates nothing.
 */
private class LaneFrame {
  var layout: LaneLayout = LaneLayout.Empty
  var glide: Float = 1f
  var reseg: Float = 1f
  var highlighted: Boolean = false
  var highlight: Long = 0L

  /** Where segment [i] begins. */
  fun start(i: Int): Float = between(layout.startsFrom[i], layout.starts[i], reseg)

  /** Length of segment [i]'s downloaded part, as a fraction of the file. */
  fun filled(i: Int): Float {
    val fill = layout.displayedFill(i, glide) - layout.starts[i]
    return fill.coerceIn(0f, (layout.ends[i] - start(i)).coerceAtLeast(0f))
  }

  /** Opacity of segment [i]: dimmed while another one is highlighted. */
  fun alpha(i: Int): Float =
    if (!highlighted || layout.keys[i] == highlight) 1f else HIGHLIGHT_DIM_ALPHA
}

private fun DrawScope.drawFills(frame: LaneFrame, palette: List<Color>, alpha: Float) {
  val layout = frame.layout
  val width = size.width
  for (i in 0 until layout.count) {
    val filled = frame.filled(i) * width
    if (filled <= 0f) continue
    drawRect(
      color = palette[layout.colors[i] % palette.size],
      topLeft = Offset(frame.start(i) * width, 0f),
      size = Size(filled, size.height),
      alpha = alpha * frame.alpha(i),
    )
  }
}

private fun DrawScope.drawSeams(
  frame: LaneFrame,
  seam: Color,
  flashColor: Color,
  flash: Float,
  alpha: Float,
) {
  val layout = frame.layout
  val seamWidth = SeamWidth.toPx()
  for (i in 1 until layout.count) {
    if (!layout.seams[i]) continue
    val topLeft = Offset(frame.start(i) * size.width - seamWidth / 2, 0f)
    val seamSize = Size(seamWidth, size.height)
    drawRect(seam, topLeft, seamSize, alpha)
    if (flash > 0f && layout.newSeams[i]) drawRect(flashColor, topLeft, seamSize, alpha * flash)
  }
}

private fun DrawScope.drawHeads(frame: LaneFrame, head: Color, stalled: Color) {
  val layout = frame.layout
  val width = size.width
  val headWidth = HeadWidth.toPx()
  val overhang = HeadOverhang.toPx()
  val glow = HeadGlow.toPx()
  val headHeight = size.height + overhang * 2
  if (width < headWidth) return
  for (i in 0 until layout.count) {
    if (!layout.unfinished[i]) continue
    val x = ((frame.start(i) + frame.filled(i)) * width)
      .coerceIn(headWidth / 2, width - headWidth / 2)
    val alpha = frame.alpha(i)
    if (layout.stalled[i]) {
      drawRect(stalled, Offset(x - headWidth / 2, -overhang), Size(headWidth, headHeight), alpha)
      continue
    }
    drawRoundRect(
      color = head,
      topLeft = Offset(x - headWidth / 2 - glow, -overhang),
      size = Size(headWidth + glow * 2, headHeight),
      cornerRadius = CornerRadius(glow + headWidth / 2),
      alpha = alpha * HEAD_GLOW_ALPHA,
    )
    drawRect(head, Offset(x - headWidth / 2, -overhang), Size(headWidth, headHeight), alpha)
  }
}

/** A band sliding across, or a still dashed track when there is no shimmer. */
private fun DrawScope.drawIndeterminate(color: Color, shimmer: Float?) {
  val width = size.width
  if (shimmer != null) {
    val band = width * SHIMMER_WIDTH
    drawRect(color, Offset(-band + (width + band) * shimmer, 0f), Size(band, size.height))
    return
  }
  val dash = maxOf(size.height * 2, StillDashMin.toPx())
  var x = 0f
  while (x < width) {
    drawRect(color, Offset(x, 0f), Size(dash, size.height), alpha = STILL_DASH_ALPHA)
    x += dash * 2
  }
}

private fun DrawScope.drawSheen(strip: LaneStripState, ember: List<Color>, progress: Float) {
  val band = size.width * SHEEN_WIDTH
  val brush = strip.sheenBrush(band, ember)
  translate(left = -band + (size.width + band) * progress) {
    drawRect(brush, size = Size(band, size.height))
  }
}

/** Colors of the lanes in each phase, resolved once per theme. */
private class LanePalettes(colors: KetchColors) {
  val downloading: List<Color> = colors.lanes
  val paused: List<Color> = colors.laneRamp(colors.status.paused.color)
  val waiting: List<Color> = colors.laneRamp(colors.status.queued.color)
  val failed: List<Color> = listOf(
    colors.textTertiary.copy(alpha = FAILED_ALPHA).compositeOver(colors.surfaceSunken)
  )

  fun of(phase: LanePhase): List<Color> = when (phase) {
    LanePhase.Downloading, LanePhase.Completed -> downloading
    LanePhase.Paused -> paused
    LanePhase.Waiting -> waiting
    LanePhase.Failed -> failed
  }
}

/** The animations and drawing caches of one strip. */
@Stable
private class LaneStripState {
  var layout: LaneLayout by mutableStateOf(LaneLayout.Empty)
    private set
  var phase: LanePhase? = null

  /** From the last snapshot's fills (0) to the current ones (1). */
  val glide = Animatable(1f)

  /** From where new seams sprang (0) to where they rest (1). */
  val reseg = Animatable(1f)

  /** Accent on new seams, fading from 1 to 0. */
  val flash = Animatable(0f)

  /** Through the seam fade and the sheen of a completion, from 0 to 1. */
  val completion = Animatable(1f)

  /** Refilled for every frame drawn. */
  val frame = LaneFrame()

  private var segments: List<Segment>? = null
  private var progress: DownloadProgress? = null
  private var stalled: Set<Long> = emptySet()
  private val clip = Path()
  private var clipSize = Size.Unspecified
  private var sheen: Brush? = null
  private var sheenWidth = -1f

  /**
   * Takes a new snapshot, starting the glide from where the fills are drawn when the current
   * glide is at [glideAt] and the re-split spring at [resegAt]. Returns whether anything changed.
   */
  fun update(
    segments: List<Segment>,
    progress: DownloadProgress?,
    stalled: Set<Long>,
    glideAt: Float,
    resegAt: Float,
  ): Boolean {
    if (segments === this.segments && progress == this.progress && stalled == this.stalled) {
      return false
    }
    this.segments = segments
    this.progress = progress
    this.stalled = stalled
    layout = laneLayout(segments, progress, stalled, layout, glide = glideAt, reseg = resegAt)
    return true
  }

  fun clipPath(size: Size, radius: Float): Path {
    if (size != clipSize) {
      clipSize = size
      clip.reset()
      clip.addRoundRect(RoundRect(0f, 0f, size.width, size.height, CornerRadius(radius)))
    }
    return clip
  }

  fun sheenBrush(width: Float, ember: List<Color>): Brush {
    val cached = sheen
    if (cached != null && width == sheenWidth) return cached
    val start = ember.first()
    val brush = Brush.horizontalGradient(
      colors = listOf(start.copy(alpha = 0f), start, ember.last(), ember.last().copy(alpha = 0f)),
      startX = 0f,
      endX = width,
    )
    sheen = brush
    sheenWidth = width
    return brush
  }
}

/**
 * Geometry of a strip for one snapshot of segments, in fractions of the file and indexed by
 * segment in byte order. It is computed when the segments change, so drawing allocates nothing.
 *
 * @property count number of segments.
 * @property keys first byte of each segment, which identifies its lane between snapshots.
 * @property starts where each segment begins.
 * @property startsFrom where each segment begins when the re-split spring starts: the old write
 *   head a new seam grows from, where an earlier seam was drawn, or [starts].
 * @property ends where each segment ends.
 * @property fillsFrom where each downloaded part ended when the glide to [fills] started.
 * @property fills where each downloaded part ends.
 * @property colors index into the lane ramp; finished neighbours share the first one's.
 * @property unfinished whether each segment still has bytes to download.
 * @property seams whether a seam separates each segment from the one before it.
 * @property newSeams whether that seam came with the latest re-split, and flashes while it lasts.
 * @property stalled whether each segment has received no data for a while.
 * @property fraction downloaded share of the file, or `null` when its size is unknown.
 * @property total size of the file in bytes, or 0 when unknown.
 * @property segmented whether the lanes are the task's segments rather than one lane standing for
 *   its progress.
 * @property resegmented whether this snapshot re-split the file, so its new seams start to spring
 *   and flash.
 */
internal class LaneLayout(
  val count: Int,
  val keys: LongArray,
  val starts: FloatArray,
  val startsFrom: FloatArray,
  val ends: FloatArray,
  val fillsFrom: FloatArray,
  val fills: FloatArray,
  val colors: IntArray,
  val unfinished: BooleanArray,
  val seams: BooleanArray,
  val newSeams: BooleanArray,
  val stalled: BooleanArray,
  val fraction: Float?,
  val total: Long,
  val segmented: Boolean,
  val resegmented: Boolean,
) {
  /** Where segment [i]'s downloaded part is drawn [glide] of the way through the glide. */
  fun displayedFill(i: Int, glide: Float): Float = between(fillsFrom[i], fills[i], glide)

  companion object {
    /** A strip with nothing to draw: no segments, and no size. */
    val Empty: LaneLayout = LaneLayout(
      count = 0,
      keys = LongArray(0),
      starts = FloatArray(0),
      startsFrom = FloatArray(0),
      ends = FloatArray(0),
      fillsFrom = FloatArray(0),
      fills = FloatArray(0),
      colors = IntArray(0),
      unfinished = BooleanArray(0),
      seams = BooleanArray(0),
      newSeams = BooleanArray(0),
      stalled = BooleanArray(0),
      fraction = null,
      total = 0,
      segmented = false,
      resegmented = false,
    )
  }
}

/**
 * Lays out [segments], or a single lane from [progress] when there are none.
 *
 * Only a [previous] snapshot of a file of the same size animates into this one. Segments whose
 * first byte matches one of its segments glide from where that one was drawn [glide] of the way
 * through its own glide. Seams that were not there before grow from the write head of the segment
 * they split. Seams of an earlier re-split that are still springing, [reseg] of the way, carry on
 * from where they are: to the end of that spring, or from where they are drawn when this
 * snapshot re-splits again.
 */
internal fun laneLayout(
  segments: List<Segment>,
  progress: DownloadProgress?,
  stalled: Set<Long> = emptySet(),
  previous: LaneLayout? = null,
  glide: Float = 1f,
  reseg: Float = 1f,
): LaneLayout {
  val sorted = when {
    segments.isNotEmpty() -> segments.sortedBy { it.start }
    progress != null && progress.totalBytes > 0 -> listOf(
      Segment(
        index = 0,
        start = 0,
        end = progress.totalBytes - 1,
        downloadedBytes = progress.downloadedBytes.coerceIn(0, progress.totalBytes),
      )
    )
    else -> return LaneLayout.Empty
  }
  val total = maxOf(sorted.maxOf { it.end } + 1, progress?.totalBytes ?: 0)
  // Only empty files, such as a torrent's zero-byte one: nothing to place.
  if (total <= 0) return LaneLayout.Empty
  val count = sorted.size
  fun share(bytes: Long): Float = (bytes.toDouble() / total).toFloat()

  val keys = LongArray(count) { sorted[it].start }
  val starts = FloatArray(count) { share(sorted[it].start) }
  val ends = FloatArray(count) { share(sorted[it].end + 1) }
  val fills = FloatArray(count) {
    val segment = sorted[it]
    share(segment.start + segment.downloadedBytes.coerceIn(0, segment.totalBytes))
  }
  val unfinished = BooleanArray(count) { !sorted[it].isComplete }
  val colors = IntArray(count)
  for (i in 0 until count) {
    colors[i] = if (i > 0 && !unfinished[i] && !unfinished[i - 1]) colors[i - 1] else i
  }
  val seamed = count <= LaneStripDefaults.MAX_SEAMED_SEGMENTS
  val seams = BooleanArray(count) { it > 0 && seamed && unfinished[it] && unfinished[it - 1] }
  val stalledLanes = BooleanArray(count) { keys[it] in stalled }

  val segmented = segments.isNotEmpty()
  val fillsFrom = fills.copyOf()
  val startsFrom = starts.copyOf()
  val newSeams = BooleanArray(count)
  var resegmented = false
  if (previous != null && previous.count > 0 && previous.total == total) {
    val matches = IntArray(count) { previous.keys.indexOfKey(keys[it]) }
    for (i in 0 until count) {
      val match = matches[i]
      if (match >= 0) {
        fillsFrom[i] = previous.displayedFill(match, glide).coerceIn(starts[i], ends[i])
      }
      // The first segments of a download that showed one lane for its progress split nothing.
      if (!previous.segmented || !seams[i] || match >= 0 && previous.seams[match]) continue
      newSeams[i] = true
      resegmented = true
      val split = previous.keys.indexAtOrBefore(keys[i])
      if (split >= 0 && previous.unfinished[split]) {
        val head = previous.displayedFill(split, glide)
        startsFrom[i] = head.coerceIn(previous.starts[split], starts[i])
      }
    }
    for (i in 0 until count) {
      val match = matches[i]
      if (match < 0 || newSeams[i]) continue
      if (resegmented) {
        startsFrom[i] = between(previous.startsFrom[match], previous.starts[match], reseg)
      } else {
        startsFrom[i] = previous.startsFrom[match]
        newSeams[i] = seams[i] && previous.newSeams[match]
      }
    }
  }
  return LaneLayout(
    count = count,
    keys = keys,
    starts = starts,
    startsFrom = startsFrom,
    ends = ends,
    fillsFrom = fillsFrom,
    fills = fills,
    colors = colors,
    unfinished = unfinished,
    seams = seams,
    newSeams = newSeams,
    stalled = stalledLanes,
    fraction = share(sorted.sumOf { it.downloadedBytes.coerceIn(0, it.totalBytes) }),
    total = total,
    segmented = segmented,
    resegmented = resegmented,
  )
}

/**
 * How far a completed strip has faded from its lanes into the completed color, from 0 to 1,
 * [completion] of the way through the fade of [fadeMillis] and the sheen of [sheenMillis] after
 * it. Without a fade, as under reduce motion, it is faded at once.
 */
internal fun completionFade(completion: Float, fadeMillis: Int, sheenMillis: Int): Float {
  if (fadeMillis <= 0) return 1f
  return (completion * (fadeMillis + sheenMillis) / fadeMillis).coerceIn(0f, 1f)
}

/**
 * Where the completion sheen is, [completion] of the way through the fade and the sheen: it
 * crosses the strip between 0 and 1 and is not drawn outside them. Without a sheen, as under
 * reduce motion, it is never drawn.
 */
internal fun completionSheen(completion: Float, fadeMillis: Int, sheenMillis: Int): Float {
  if (sheenMillis <= 0) return 1f
  return (completion * (fadeMillis + sheenMillis) - fadeMillis) / sheenMillis
}

/**
 * Downloaded share of the file a strip shows, or `null` when the size is unknown; a completed
 * task is whole.
 */
internal fun laneFraction(
  segments: List<Segment>,
  progress: DownloadProgress?,
  phase: LanePhase,
): Float? {
  if (phase == LanePhase.Completed) return 1f
  if (segments.isEmpty()) {
    return progress?.takeIf { it.totalBytes > 0 }?.let { it.percent.coerceIn(0f, 1f) }
  }
  val total = segments.sumOf { it.totalBytes }
  if (total <= 0) return null
  val done = segments.sumOf { it.downloadedBytes.coerceIn(0, it.totalBytes) }
  return (done.toDouble() / total).toFloat()
}

/**
 * What a strip says to a screen reader, such as "8 connections, 6 active, 42 percent". Only a
 * downloading strip counts its active connections.
 */
internal fun laneStripDescription(
  segments: List<Segment>,
  progress: DownloadProgress?,
  phase: LanePhase,
  stalled: Int = 0,
): UiText {
  val parts = ArrayList<UiText>(4)
  val connections = segments.size
  if (connections > 0) parts += Res.plurals.component_lanes_connections.text(connections)
  if (connections > 0 && phase == LanePhase.Downloading) {
    parts += Res.plurals.component_lanes_active.text(segments.count { !it.isComplete })
  }
  val fraction = laneFraction(segments, progress, phase)
  parts += if (fraction != null) {
    Res.plurals.component_lanes_percent.text((fraction * 100).toInt())
  } else {
    Res.string.component_lanes_size_unknown.text()
  }
  if (stalled > 0) parts += Res.plurals.component_lanes_stalled.text(stalled)
  return parts.joinText(DESCRIPTION_SEPARATOR)
}

/** Separator of the parts of [laneStripDescription]. */
private const val DESCRIPTION_SEPARATOR = ", "

/** Index of the last key at or before [key] in these ascending keys, or -1. */
private fun LongArray.indexAtOrBefore(key: Long): Int {
  val found = indexOfKey(key)
  return if (found >= 0) found else -found - 2
}

/** Index of [key] in these ascending keys, or `-(insertion point) - 1` when it is absent. */
private fun LongArray.indexOfKey(key: Long): Int {
  var low = 0
  var high = size - 1
  while (low <= high) {
    val mid = (low + high) ushr 1
    val value = this[mid]
    when {
      value < key -> low = mid + 1
      value > key -> high = mid - 1
      else -> return mid
    }
  }
  return -(low + 1)
}

/** The value [fraction] of the way from [from] to [to]. */
internal fun between(from: Float, to: Float, fraction: Float): Float = from + (to - from) * fraction

private const val SEAM_FLASH_MILLIS = 400
private const val SHIMMER_MILLIS = 1200
private const val SHIMMER_WIDTH = 0.3f
private const val SHEEN_WIDTH = 0.4f
private const val HEAD_GLOW_ALPHA = 0.3f
private const val HIGHLIGHT_DIM_ALPHA = 0.35f
private const val COMPLETED_ALPHA = 0.7f
private const val FAILED_ALPHA = 0.4f
private const val STILL_DASH_ALPHA = 0.5f
private val SeamWidth = 1.dp
private val HeadWidth = 2.dp
private val HeadOverhang = 2.dp
private val HeadGlow = 2.dp
private val StillDashMin = 6.dp
