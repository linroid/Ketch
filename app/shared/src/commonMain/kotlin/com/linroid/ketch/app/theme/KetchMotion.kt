package com.linroid.ketch.app.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Immutable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp

/**
 * Durations, easings and springs. With [reduced] set, every duration is 0 and the springs
 * snap, so nothing moves.
 *
 * @property micro hover and press colors, in milliseconds.
 * @property short toggles, segmented thumbs, tab switches and chip morphs.
 * @property medium inspector slide, list placement, lane resize and banners.
 * @property long sheet and dialog enter.
 * @property longExit sheet and dialog exit.
 * @property xlong onboarding.
 * @property easeStandard the default easing.
 * @property easeDecelerate entering elements.
 * @property easeAccelerate exiting elements.
 * @property progressSpring progress fills and lane widths.
 * @property placementSpring list item placement (`Modifier.animateItem`).
 * @property headGlide write heads, matching the engine's 200 ms progress cadence.
 * @property pulse period of the Downloading and Connecting pulse; 0 when there is none.
 * @property pulseAlpha starting alpha of the pulse halo, which fades to 0.
 * @property pulseGrowth how far the pulse halo grows past the dot.
 * @property reduced whether motion is reduced (system setting or the app's switch).
 */
@Immutable
data class KetchMotion(
  val micro: Int = 90,
  val short: Int = 150,
  val medium: Int = 220,
  val long: Int = 320,
  val longExit: Int = 200,
  val xlong: Int = 480,
  val easeStandard: Easing = CubicBezierEasing(0.2f, 0f, 0f, 1f),
  val easeDecelerate: Easing = CubicBezierEasing(0.05f, 0.7f, 0.1f, 1f),
  val easeAccelerate: Easing = CubicBezierEasing(0.3f, 0f, 0.8f, 0.15f),
  val progressSpring: FiniteAnimationSpec<Float> = spring(dampingRatio = 1f, stiffness = 200f),
  val placementSpring: FiniteAnimationSpec<IntOffset> = spring(stiffness = 400f),
  val headGlide: FiniteAnimationSpec<Float> = tween(200, easing = LinearEasing),
  val pulse: Int = 1600,
  val pulseAlpha: Float = 0.28f,
  val pulseGrowth: Dp = 5.dp,
  val reduced: Boolean = false,
)

/** The motion tokens, or the still version when [reduceMotion] is set. */
fun ketchMotion(reduceMotion: Boolean = false): KetchMotion {
  if (!reduceMotion) return KetchMotion()
  return KetchMotion(
    micro = 0,
    short = 0,
    medium = 0,
    long = 0,
    longExit = 0,
    xlong = 0,
    progressSpring = snap(),
    placementSpring = snap(),
    headGlide = snap(),
    pulse = 0,
    reduced = true,
  )
}
