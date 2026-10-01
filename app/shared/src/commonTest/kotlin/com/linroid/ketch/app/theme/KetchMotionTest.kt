package com.linroid.ketch.app.theme

import androidx.compose.animation.core.SnapSpec
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class KetchMotionTest {
  @Test fun ketchMotion_reduceMotion_stopsEveryAnimation() {
    val motion = ketchMotion(reduceMotion = true)
    val durations = with(motion) { listOf(micro, short, medium, long, longExit, xlong, pulse) }
    assertTrue(durations.all { it == 0 })
    assertIs<SnapSpec<*>>(motion.progressSpring)
    assertIs<SnapSpec<*>>(motion.placementSpring)
    assertIs<SnapSpec<*>>(motion.headGlide)
    assertTrue(motion.reduced)
  }
}
