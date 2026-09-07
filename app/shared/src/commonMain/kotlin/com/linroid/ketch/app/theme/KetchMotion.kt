package com.linroid.ketch.app.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.runtime.Immutable

@Immutable
data class KetchMotion(
  val durationMicro: Int = 80,
  val durationShort: Int = 120,
  val durationMedium: Int = 200,
  val durationLong: Int = 320,
  val durationExtra: Int = 480,

  val easeStandard: Easing = CubicBezierEasing(0.20f, 0.00f, 0.00f, 1.00f),
  val easeEmphasized: Easing = CubicBezierEasing(0.20f, 0.00f, 0.00f, 1.00f),
  val easeDecelerate: Easing = CubicBezierEasing(0.00f, 0.00f, 0.00f, 1.00f),
  val easeAccelerate: Easing = CubicBezierEasing(0.30f, 0.00f, 1.00f, 1.00f),
  val easeLinear: Easing = Easing { it },
)

fun ketchMotion(): KetchMotion = KetchMotion()
