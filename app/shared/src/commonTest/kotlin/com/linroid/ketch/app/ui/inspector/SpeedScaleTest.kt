package com.linroid.ketch.app.ui.inspector

import com.linroid.ketch.api.SpeedLimit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SpeedScaleTest {
  @Test
  fun fractionOf_ends_spanTheTrack() {
    assertEquals(0f, SpeedScale.fractionOf(SpeedScale.Min))
    assertEquals(1f, SpeedScale.fractionOf(SpeedScale.Max), 1e-6f)
    assertEquals(1f, SpeedScale.fractionOf(SpeedLimit.Unlimited))
    assertEquals(0f, SpeedScale.fractionOf(SpeedLimit.kbps(1)))
  }

  @Test
  fun fractionOf_logScale_putsEachDoublingTheSameDistanceApart() {
    val one = SpeedScale.fractionOf(SpeedLimit.mbps(1))
    val two = SpeedScale.fractionOf(SpeedLimit.mbps(2))
    val four = SpeedScale.fractionOf(SpeedLimit.mbps(4))

    assertEquals(two - one, four - two, 1e-5f)
  }

  @Test
  fun limitAt_nearADetent_snapsToIt() {
    for (detent in SpeedScale.Detents) {
      val near = SpeedScale.fractionOf(detent) + SpeedScale.SNAP / 2
      assertEquals(detent, SpeedScale.limitAt(near))
    }
  }

  @Test
  fun limitAt_betweenDetents_keepsTwoSignificantDigits() {
    val between = (SpeedScale.fractionOf(SpeedLimit.mbps(2)) +
      SpeedScale.fractionOf(SpeedLimit.mbps(5))) / 2

    val limit = SpeedScale.limitAt(between)

    // The geometric middle of 2 and 5 MB/s is about 3.16 MB/s.
    assertEquals(SpeedLimit.of((3.2 * MIB).toLong()), limit)
  }

  @Test
  fun limitAt_endsAndBeyond_clampToTheEnds() {
    assertEquals(SpeedScale.Min, SpeedScale.limitAt(-1f))
    assertEquals(SpeedScale.Max, SpeedScale.limitAt(2f))
  }

  @Test
  fun limitAt_everyPosition_staysWithinTheScale() {
    for (step in 0..100) {
      val bytes = SpeedScale.limitAt(step / 100f).bytesPerSecond
      assertTrue(bytes in SpeedScale.Min.bytesPerSecond..SpeedScale.Max.bytesPerSecond)
    }
  }

  @Test
  fun step_arrowKeys_moveBetweenDetentsAndEnds() {
    assertEquals(SpeedLimit.mbps(2), SpeedScale.step(SpeedLimit.mbps(1), 1))
    assertEquals(SpeedLimit.mbps(5), SpeedScale.step(SpeedLimit.kbps(3000), 1))
    assertEquals(SpeedLimit.kbps(512), SpeedScale.step(SpeedLimit.mbps(1), -1))
    assertEquals(SpeedScale.Min, SpeedScale.step(SpeedScale.Min, -1))
    assertEquals(SpeedScale.Max, SpeedScale.step(SpeedLimit.Unlimited, 1))
    assertEquals(SpeedLimit.mbps(50), SpeedScale.step(SpeedLimit.Unlimited, -1))
  }

  private companion object {
    const val MIB = 1024.0 * 1024.0
  }
}
