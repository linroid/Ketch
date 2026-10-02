package com.linroid.ketch.app.components

import kotlin.test.Test
import kotlin.test.assertEquals

class SailLanesIllustrationTest {
  @Test
  fun sailLaneFill_beforeItsTurn_isEmpty() {
    assertEquals(0f, sailLaneFill(lane = 3, time = 3 * 90f))
  }

  @Test
  fun sailLaneFill_partWay_growsWithTime() {
    assertEquals(0.5f, sailLaneFill(lane = 3, time = 3 * 90f + 800f), TOLERANCE)
  }

  @Test
  fun sailLaneFill_lastLaneAfterItsFill_isFull() {
    assertEquals(1f, sailLaneFill(lane = 9, time = 9 * 90f + 1600f))
  }

  @Test
  fun sailFillAlpha_whileHolding_staysOpaque() {
    assertEquals(1f, sailFillAlpha(time = 4400f))
  }

  @Test
  fun sailFillAlpha_afterTheHold_fadesOutBeforeTheLoopEnds() {
    assertEquals(0.5f, sailFillAlpha(time = 4705f), TOLERANCE)
    assertEquals(0f, sailFillAlpha(time = 5000f), TOLERANCE)
  }

  private companion object {
    const val TOLERANCE = 0.001f
  }
}
